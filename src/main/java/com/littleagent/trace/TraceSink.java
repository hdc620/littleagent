package com.littleagent.trace;

/** trace 事件出口。实现必须线程安全。 */
@FunctionalInterface
public interface TraceSink {

    void accept(TraceEvent event);
}
