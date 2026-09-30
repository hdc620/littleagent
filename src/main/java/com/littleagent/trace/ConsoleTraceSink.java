package com.littleagent.trace;

/** 把 trace 打到控制台（调试用，默认关闭）。 */
public final class ConsoleTraceSink implements TraceSink {

    private final boolean verbose;

    public ConsoleTraceSink(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public void accept(TraceEvent event) {
        System.out.println("[trace] " + event.render());
        if (verbose && !event.data().isEmpty()) {
            System.out.println("        " + event.data());
        }
    }
}
