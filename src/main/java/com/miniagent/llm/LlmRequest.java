package com.miniagent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次 LLM 调用的请求参数。
 *
 * @param model       模型名，null 表示用客户端默认模型
 * @param messages    上下文消息（已按 OpenAI 顺序规则排好）
 * @param tools       暴露给模型的工具声明，空列表表示本次不开放工具
 * @param temperature 采样温度
 * @param maxTokens   最大输出 token
 * @param toolChoice  auto / none / required（本项目只在「强制收尾」时用 none）
 */
public record LlmRequest(
        String model,
        List<Message> messages,
        List<ToolSpec> tools,
        Double temperature,
        Integer maxTokens,
        String toolChoice) {

    public LlmRequest {
        messages = List.copyOf(messages == null ? List.of() : messages);
        tools = List.copyOf(tools == null ? List.of() : tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int estimatedInputTokens() {
        int total = 0;
        for (Message m : messages) {
            total += m.estimatedTokens();
        }
        for (ToolSpec spec : tools) {
            total += com.miniagent.util.TokenEstimator.estimate(spec.name())
                    + com.miniagent.util.TokenEstimator.estimate(spec.description())
                    + com.miniagent.util.TokenEstimator.estimate(String.valueOf(spec.parameters()));
        }
        return total;
    }

    public static final class Builder {
        private String model;
        private List<Message> messages = new ArrayList<>();
        private List<ToolSpec> tools = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;
        private String toolChoice;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = new ArrayList<>(messages);
            return this;
        }

        public Builder addMessage(Message message) {
            this.messages.add(Objects.requireNonNull(message));
            return this;
        }

        public Builder tools(List<ToolSpec> tools) {
            this.tools = new ArrayList<>(tools);
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder toolChoice(String toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(model, messages, tools, temperature, maxTokens, toolChoice);
        }
    }
}
