package com.littleagent.llm;

/** 对话角色（对应 OpenAI Chat Completions 的 role 字段）。 */
public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireName;

    Role(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Role fromWire(String wire) {
        for (Role r : values()) {
            if (r.wireName.equalsIgnoreCase(wire)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知 role: " + wire);
    }
}
