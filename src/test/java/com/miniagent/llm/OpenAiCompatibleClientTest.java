package com.miniagent.llm;

import com.miniagent.error.ConfigurationException;
import com.miniagent.trace.Tracer;
import com.miniagent.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 客户端的行为测试：用本地 stub server 验证 wire format、响应解析、重试与错误分类。
 * 不依赖外网，也不需要 API Key，因此可以放进常规 {@code mvn test}。
 */
class OpenAiCompatibleClientTest {

    /** (状态码, 响应体) 队列 */
    private final Deque<StubResponse> script = new ArrayDeque<>();
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    private HttpServer server;
    private String baseUrl;
    private OpenAiCompatibleClient client;

    private record StubResponse(int status, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new OpenAiCompatibleClient(baseUrl, "test-key", "deepseek-chat",
                Duration.ofSeconds(5), 2, Duration.ofMillis(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        callCount.incrementAndGet();
        capturedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        StubResponse response = script.isEmpty()
                ? new StubResponse(200, successBody("默认回复"))
                : script.pollFirst();
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ---------------------------------------------------------- 请求 wire format

    @Test
    @DisplayName("请求体符合 OpenAI Chat Completions 规范（messages / tools / tool_choice）")
    void buildsCorrectRequestBody() {
        ToolCall call = new ToolCall("call_1", "calculator", "{\"expression\":\"1+1\"}");
        LlmRequest request = LlmRequest.builder()
                .model("deepseek-chat")
                .temperature(0.2)
                .maxTokens(256)
                .addMessage(Message.system("系统提示"))
                .addMessage(Message.user("帮我算 1+1"))
                .addMessage(Message.assistantToolCalls("我来计算", null, List.of(call)))
                .addMessage(Message.tool("call_1", "calculator", "1+1 = 2"))
                .tools(List.of(new ToolSpec("calculator", "做数学计算", schema())))
                .build();

        client.chat(request, Tracer.noop());

        var body = Json.parse(capturedBodies.get(0));
        assertEquals("deepseek-chat", body.path("model").asText());
        assertEquals(0.2, body.path("temperature").asDouble(), 1e-9);
        assertEquals(256, body.path("max_tokens").asInt());
        assertFalse(body.path("stream").asBoolean(true));
        assertEquals(4, body.path("messages").size());
        assertEquals("system", body.path("messages").get(0).path("role").asText());

        var assistant = body.path("messages").get(2);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("calculator", assistant.path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals("{\"expression\":\"1+1\"}",
                assistant.path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());

        var tool = body.path("messages").get(3);
        assertEquals("tool", tool.path("role").asText());
        assertEquals("call_1", tool.path("tool_call_id").asText());
        assertEquals("1+1 = 2", tool.path("content").asText());

        var tools = body.path("tools");
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).path("type").asText());
        assertEquals("calculator", tools.get(0).path("function").path("name").asText());
        assertEquals("object", tools.get(0).path("function").path("parameters").path("type").asText());
        assertEquals("auto", body.path("tool_choice").asText());
    }

    @Test
    @DisplayName("思维链（reasoning）绝不回灌给 API")
    void neverSendsReasoningBack() {
        LlmRequest request = LlmRequest.builder()
                .addMessage(Message.system("系统"))
                .addMessage(Message.assistant("对用户说的话", "这是内部推理过程，不应回灌"))
                .build();

        client.chat(request, Tracer.noop());

        String body = capturedBodies.get(0);
        assertTrue(body.contains("对用户说的话"));
        assertFalse(body.contains("这是内部推理过程"));
    }

    @Test
    @DisplayName("未开放工具时不发送 tools / tool_choice 字段")
    void omitsToolsWhenEmpty() {
        client.chat(LlmRequest.builder().addMessage(Message.user("你好")).build(), Tracer.noop());
        var body = Json.parse(capturedBodies.get(0));
        assertFalse(body.has("tools"));
        assertFalse(body.has("tool_choice"));
    }

    // -------------------------------------------------------------- 响应解析

    @Test
    @DisplayName("解析 reasoning_content / tool_calls / usage")
    void parsesResponse() {
        ObjectNode function = Json.obj().put("name", "calculator").put("arguments", "{\"expression\":\"2+2\"}");
        ObjectNode call = Json.obj().put("id", "call_abc").put("type", "function").set("function", function);
        ObjectNode message = Json.obj().put("content", "我来算一下").put("reasoning_content", "用户想算加法")
                .set("tool_calls", Json.arr().add(call));
        ObjectNode root = Json.obj().set("choices", Json.arr().add(
                Json.obj().put("finish_reason", "tool_calls").set("message", message)));
        root.set("usage", Json.obj().put("prompt_tokens", 100).put("completion_tokens", 20).put("total_tokens", 120));
        script.add(new StubResponse(200, root.toString()));

        LlmResponse response = client.chat(LlmRequest.builder().addMessage(Message.user("2+2")).build(), Tracer.noop());

        assertEquals("我来算一下", response.content());
        assertEquals("用户想算加法", response.reasoning());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(100, response.usage().promptTokens());
        assertEquals(20, response.usage().completionTokens());
        assertEquals(120, response.usage().totalTokens());
        assertEquals(1, response.toolCalls().size());
        assertEquals("calculator", response.toolCalls().get(0).name());
        assertEquals("call_abc", response.toolCalls().get(0).id());
    }

    @Test
    @DisplayName("content 为 null（纯工具调用）不会抛 NPE")
    void handlesNullContent() {
        script.add(new StubResponse(200, successBody(null)));
        LlmResponse response = client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop());
        assertNull(response.content());
        assertEquals(0, response.toolCalls().size());
    }

    @Test
    @DisplayName("arguments 为对象形态时归一化成 JSON 字符串")
    void normalizesObjectArguments() {
        ObjectNode function = Json.obj().put("name", "weather").set("arguments", Json.obj().put("city", "北京"));
        ObjectNode call = Json.obj().put("id", "c1").set("function", function);
        ObjectNode root = Json.obj().set("choices", Json.arr().add(
                Json.obj().set("message", Json.obj().set("tool_calls", Json.arr().add(call)))));
        script.add(new StubResponse(200, root.toString()));

        LlmResponse response = client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop());
        assertEquals("{\"city\":\"北京\"}", response.toolCalls().get(0).argumentsJson());
    }

    // ---------------------------------------------------------- 重试与错误分类

    @Test
    @DisplayName("429 限流后重试成功")
    void retriesOn429() {
        script.add(new StubResponse(429, "{\"error\":{\"message\":\"rate limited\"}}"));
        script.add(new StubResponse(200, successBody("重试成功")));

        LlmResponse response = client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop());

        assertEquals("重试成功", response.content());
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("5xx 重试到上限后抛出 LLM_SERVER_ERROR")
    void retriesServerErrorsThenFails() {
        script.add(new StubResponse(500, "{\"error\":{\"message\":\"boom\"}}"));
        script.add(new StubResponse(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        script.add(new StubResponse(502, "{\"error\":{\"message\":\"bad gateway\"}}"));

        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop()));

        assertEquals("LLM_SERVER_ERROR", error.code());
        assertTrue(error.retryable());
        assertEquals(3, callCount.get(), "1 次原始请求 + 2 次重试");
    }

    @Test
    @DisplayName("401 鉴权失败不重试（重试没有意义）")
    void doesNotRetryAuthErrors() {
        script.add(new StubResponse(401, "{\"error\":{\"message\":\"invalid api key\"}}"));

        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop()));

        assertEquals("LLM_AUTH_ERROR", error.code());
        assertFalse(error.retryable());
        assertEquals(1, callCount.get());
        assertTrue(error.getMessage().contains("invalid api key"));
    }

    @Test
    @DisplayName("400 请求不合法不重试")
    void doesNotRetryBadRequest() {
        script.add(new StubResponse(400, "{\"error\":{\"message\":\"messages is required\"}}"));
        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop()));
        assertEquals("LLM_BAD_REQUEST", error.code());
        assertEquals(1, callCount.get());
    }

    @Test
    @DisplayName("响应不是合法 JSON 时返回 LLM_BAD_RESPONSE")
    void rejectsMalformedResponse() {
        script.add(new StubResponse(200, "<html>网关错误页</html>"));
        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop()));
        assertEquals("LLM_BAD_RESPONSE", error.code());
    }

    @Test
    @DisplayName("响应缺少 choices 时返回 LLM_BAD_RESPONSE")
    void rejectsMissingChoices() {
        script.add(new StubResponse(200, "{\"id\":\"x\"}"));
        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop()));
        assertEquals("LLM_BAD_RESPONSE", error.code());
    }

    // ------------------------------------------------------------------ 其它

    @Test
    @DisplayName("URL 规整：base url 自动补 /v1/chat/completions")
    void normalizesEndpoint() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleClient.toEndpoint("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleClient.toEndpoint("https://api.deepseek.com/"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleClient.toEndpoint("https://api.deepseek.com/v1"));
        assertEquals("https://proxy.example.com/chat/completions",
                OpenAiCompatibleClient.toEndpoint("https://proxy.example.com/chat/completions"));
    }

    @Test
    @DisplayName("缺少 API Key 时立即报配置错误，不发请求")
    void requiresApiKey() {
        assertThrows(ConfigurationException.class,
                () -> new OpenAiCompatibleClient(baseUrl, "  ", "m", Duration.ofSeconds(1), 0, Duration.ofMillis(1)));
    }

    @Test
    @DisplayName("网络不可达时抛出可重试的网络错误")
    void reportsNetworkError() {
        OpenAiCompatibleClient offline = new OpenAiCompatibleClient("http://127.0.0.1:1", "k", "m",
                Duration.ofMillis(300), 0, Duration.ofMillis(5));
        LlmException error = assertThrows(LlmException.class, () -> offline.chat(
                LlmRequest.builder().addMessage(Message.user("x")).build(), Tracer.noop()));
        assertEquals("LLM_NETWORK_ERROR", error.code());
        assertTrue(error.retryable());
    }

    @Test
    @DisplayName("trace 记录请求与响应事件")
    void recordsWireTrace() {
        Tracer tracer = Tracer.of("w1");
        client.chat(LlmRequest.builder().addMessage(Message.user("x")).build(), tracer);
        List<String> types = tracer.events().stream().map(e -> e.type()).toList();
        assertTrue(types.contains("llm_request"));
        assertTrue(types.contains("llm_response"));
    }

    private static String successBody(String content) {
        ObjectNode message = Json.obj();
        if (content == null) {
            message.putNull("content");
        } else {
            message.put("content", content);
        }
        ObjectNode root = Json.obj().set("choices", Json.arr().add(
                Json.obj().put("finish_reason", "stop").set("message", message)));
        root.set("usage", Json.obj().put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15));
        return root.toString();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode schema() {
        var schema = Json.obj();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.set("expression", Json.obj().put("type", "string"));
        schema.putArray("required").add("expression");
        return schema;
    }
}
