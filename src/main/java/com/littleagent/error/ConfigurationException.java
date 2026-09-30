package com.littleagent.error;

/** 配置缺失或非法（例如没有 API Key、参数越界）。 */
public class ConfigurationException extends AgentException {

    public ConfigurationException(String message) {
        super("CONFIG_ERROR", message);
    }
}
