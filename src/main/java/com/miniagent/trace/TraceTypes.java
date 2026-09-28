package com.miniagent.trace;

/** trace 事件类型常量（集中定义，方便测试断言与日志检索）。 */
public final class TraceTypes {

    public static final String SESSION_CREATED = "session_created";
    public static final String USER_INPUT = "user_input";
    public static final String CONTEXT_BUILT = "context_built";
    public static final String MEMORY_RECALL = "memory_recall";
    public static final String CONTEXT_COMPRESSED = "context_compressed";
    public static final String CONTEXT_COMPRESS_FAILED = "context_compress_failed";
    public static final String LLM_REQUEST = "llm_request";
    public static final String LLM_RESPONSE = "llm_response";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String TOOL_BLOCKED = "tool_blocked";
    public static final String LOOP_STEP = "loop_step";
    public static final String FINAL_ANSWER = "final_answer";
    public static final String MAX_STEPS_REACHED = "max_steps_reached";
    public static final String ERROR = "error";

    private TraceTypes() {
    }
}
