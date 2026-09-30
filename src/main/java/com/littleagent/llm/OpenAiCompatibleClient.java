package com.littleagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.littleagent.trace.TraceTypes;
import com.littleagent.trace.Tracer;
import com.littleagent.util.Json;
import com.littleagent.util.Texts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 真实 LLM 客户端：调用任意 OpenAI 兼容的 {@code /v1/chat/completions} 接口（默认 DeepSeek）。
 *
 * <p>职责边界（有意保持窄）：
 * <ul>
 *   <li>把 {@link LlmRequest} 序列化成 OpenAI wire format；</li>
 *   <li>HTTP 传输 + 超时 + 指数退避重试（仅对网络错误 / 429 / 5xx）；</li>
 *   <li>把响应解析回 {@link LlmResponse}，并做基础结构校验；</li>
 *   <li>写 trace：请求摘要、响应摘要、重试、token 用量。</li>
 * </ul>
 * 不含任何 Agent 逻辑 —— 循环、工具调度、上下文管理都在 Runtime 侧。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final System.Logger LOG = System.getLogger(OpenAiCompatibleClient.class.getName());

    private final String endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final HttpClient http;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String defaultModel,
                                  Duration requestTimeout, int maxRetries, Duration initialBackoff) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new com.littleagent.error.ConfigurationException(
                    "缺少 API Key：请设置环境变量 DEEPSEEK_API_KEY，或写入 .env.local");
        }
        this.endpoint = toEndpoint(baseUrl);
        this.apiKey = apiKey.strip();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "deepseek-chat" : defaultModel;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoff = initialBackoff == null ? Duration.ofMillis(500) : initialBackoff;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static OpenAiCompatibleClient deepSeek(String apiKey, String model) {
        return new OpenAiCompatibleClient("https://api.deepseek.com", apiKey, model,
                Duration.ofSeconds(60), 2, Duration.ofMillis(500));
    }

    public String endpoint() {
        return endpoint;
    }

    @Override
    public LlmResponse chat(LlmRequest request, Tracer tracer) {
        String model = request.model() == null || request.model().isBlank() ? defaultModel : request.model();
        String body = Json.write(buildBody(request, model));
        tracer.event(TraceTypes.LLM_REQUEST, "POST " + endpoint,
                Tracer.data("model", model,
                        "messages", request.messages().size(),
                        "tools", request.tools().size(),
                        "estimatedInputTokens", request.estimatedInputTokens(),
                        "temperature", request.temperature(),
                        "toolChoice", request.toolChoice()));

        LlmException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = send(body);
                long costMs = (System.nanoTime() - start) / 1_000_000L;
                int status = response.statusCode();
                if (status / 100 == 2) {
                    LlmResponse parsed = parseResponse(response.body());
                    tracer.event(TraceTypes.LLM_RESPONSE, "HTTP 200 in " + costMs + "ms",
                            Tracer.data("finishReason", parsed.finishReason(),
                                    "promptTokens", parsed.usage().promptTokens(),
                                    "completionTokens", parsed.usage().completionTokens(),
                                    "toolCalls", parsed.toolCalls().size(),
                                    "attempt", attempt + 1,
                                    "latencyMs", costMs,
                                    "contentPreview", Texts.oneLine(parsed.content(), 120),
                                    "reasoningPreview", Texts.oneLine(parsed.reasoning(), 120)));
                    return parsed;
                }
                lastError = classify(status, response.body(), response.headers().firstValue("Retry-After").orElse(null));
            } catch (IOException e) {
                lastError = LlmException.network("网络错误: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LlmException.network("调用被中断", e);
            }

            boolean canRetry = lastError.retryable() && attempt < maxRetries;
            tracer.event(TraceTypes.ERROR, "LLM 调用失败（第 " + (attempt + 1) + " 次）",
                    Tracer.data("code", lastError.code(), "retryable", lastError.retryable(),
                            "willRetry", canRetry, "message", Texts.oneLine(lastError.getMessage(), 200)));
            if (!canRetry) {
                throw lastError;
            }
            sleep(backoffFor(attempt, lastError));
        }
        throw lastError == null ? LlmException.network("未知网络错误", null) : lastError;
    }

    private HttpResponse<String> send(String body) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        return http.send(httpRequest, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.network("重试等待被中断", e);
        }
    }

    private Duration backoffFor(int attempt, LlmException error) {
        if (error.code().equals("LLM_RATE_LIMITED")) {
            long millis = initialBackoff.toMillis() * (1L << attempt) * 2;
            return Duration.ofMillis(Math.min(millis, 8_000));
        }
        long millis = initialBackoff.toMillis() * (1L << attempt);
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, millis / 4));
        return Duration.ofMillis(Math.min(millis + jitter, 8_000));
    }

    // ---------------------------------------------------------------- request

    ObjectNode buildBody(LlmRequest request, String model) {
        ObjectNode root = Json.obj();
        root.put("model", model);

        ArrayNode messages = root.putArray("messages");
        for (Message message : request.messages()) {
            messages.add(toWire(message));
        }

        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode function = Json.obj();
                function.put("name", spec.name());
                function.put("description", spec.description());
                function.set("parameters", spec.parameters() == null ? objectSchemaPlaceholder() : spec.parameters());
                ObjectNode wrapper = Json.obj();
                wrapper.put("type", "function");
                wrapper.set("function", function);
                tools.add(wrapper);
            }
            root.put("tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice());
        }

        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        root.put("stream", false);
        return root;
    }

    /**
     * Message -> OpenAI wire format。
     *
     * <p>关键约定：{@code reasoning}（思维链）<b>永远不回灌</b>。DeepSeek 官方也要求不要把
     * reasoning_content 传回下一轮，且它属于 Runtime 观测信息而非对话内容。
     */
    ObjectNode toWire(Message message) {
        ObjectNode node = Json.obj();
        node.put("role", message.role().wireName());
        switch (message.role()) {
            case ASSISTANT -> {
                if (!message.content().isBlank()) {
                    node.put("content", message.content());
                } else if (message.hasToolCalls()) {
                    node.putNull("content");
                } else {
                    node.put("content", "");
                }
                if (message.hasToolCalls()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode function = Json.obj();
                        function.put("name", call.name());
                        function.put("arguments", call.argumentsJson());
                        ObjectNode callNode = Json.obj();
                        callNode.put("id", call.id());
                        callNode.put("type", "function");
                        callNode.set("function", function);
                        calls.add(callNode);
                    }
                }
            }
            case TOOL -> {
                node.put("content", message.content());
                if (message.toolCallId() != null) {
                    node.put("tool_call_id", message.toolCallId());
                }
                if (message.toolName() != null) {
                    node.put("name", message.toolName());
                }
            }
            default -> node.put("content", message.content());
        }
        return node;
    }

    // --------------------------------------------------------------- response

    LlmResponse parseResponse(String rawBody) {
        JsonNode root = Json.parseOrNull(rawBody);
        if (root == null) {
            throw LlmException.badResponse("响应不是合法 JSON: " + Texts.oneLine(rawBody, 200));
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw LlmException.badResponse("网关返回错误: " + Texts.oneLine(error.toString(), 300));
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw LlmException.badResponse("响应缺少 choices: " + Texts.oneLine(rawBody, 200));
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");

        String content = optText(message, "content");
        String reasoning = firstNonNull(optText(message, "reasoning_content"), optText(message, "reasoning"));

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode callsNode = message.get("tool_calls");
        if (callsNode != null && callsNode.isArray()) {
            for (JsonNode callNode : callsNode) {
                JsonNode function = callNode.path("function");
                String name = function.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String arguments = function.path("arguments").isMissingNode()
                        ? "{}"
                        : normalizeArguments(function.path("arguments"));
                toolCalls.add(new ToolCall(callNode.path("id").asText(null), name, arguments));
            }
        }

        String finishReason = choice.path("finish_reason").isNull() ? null : choice.path("finish_reason").asText(null);
        Usage usage = parseUsage(root.get("usage"));
        return new LlmResponse(content, reasoning, toolCalls, finishReason, usage, rawBody);
    }

    private static Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return Usage.ZERO;
        }
        int prompt = usageNode.path("prompt_tokens").asInt(0);
        int completion = usageNode.path("completion_tokens").asInt(0);
        int total = usageNode.path("total_tokens").asInt(prompt + completion);
        return new Usage(prompt, completion, total);
    }

    /** 有些网关把 arguments 序列化成字符串，这里统一成字符串形态。 */
    private static String normalizeArguments(JsonNode arguments) {
        if (arguments.isTextual()) {
            return arguments.asText();
        }
        return arguments.toString();
    }

    private static String optText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonNull(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    // ----------------------------------------------------------------- errors

    private LlmException classify(int status, String body, String retryAfter) {
        String detail = errorMessage(body);
        return switch (status) {
            case 401, 403 -> LlmException.auth("HTTP " + status + " 鉴权失败: " + detail);
            case 429 -> LlmException.rateLimited("HTTP 429 限流" + (retryAfter == null ? "" : "（Retry-After=" + retryAfter + "）") + ": " + detail);
            case 400, 404, 422 -> LlmException.badRequest("HTTP " + status + " 请求不合法: " + detail);
            default -> status >= 500
                    ? LlmException.serverError("HTTP " + status + " 服务端错误: " + detail)
                    : LlmException.badRequest("HTTP " + status + ": " + detail);
        };
    }

    private String errorMessage(String body) {
        JsonNode root = Json.parseOrNull(body);
        if (root != null) {
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                String message = error.path("message").asText(null);
                return Texts.oneLine(message == null ? error.toString() : message, 300);
            }
            String message = root.path("message").asText(null);
            if (message != null) {
                return Texts.oneLine(message, 300);
            }
        }
        return Texts.oneLine(body, 300);
    }

    private static ObjectNode objectSchemaPlaceholder() {
        ObjectNode schema = Json.obj();
        schema.put("type", "object");
        schema.set("properties", Json.obj());
        return schema;
    }

    /** 把 base url 规整成 chat/completions 端点。 */
    static String toEndpoint(String baseUrl) {
        String base = Optional.ofNullable(baseUrl).orElse("https://api.deepseek.com").strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    static Map<String, Object> summaryOf(LlmResponse response) {
        return Tracer.data("finishReason", response.finishReason(),
                "toolCalls", response.toolCalls().size(),
                "reasoning", response.reasoning() != null);
    }
}
