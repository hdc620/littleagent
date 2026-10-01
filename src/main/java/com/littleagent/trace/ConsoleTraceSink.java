package com.littleagent.trace;

import java.io.PrintStream;

/** 把 trace 打到控制台（调试用，默认关闭）。 */
public final class ConsoleTraceSink implements TraceSink {

    private final boolean verbose;
    private final PrintStream out;

    public ConsoleTraceSink(boolean verbose) {
        this(verbose, System.out);
    }

    /** 注入输出流（测试时重定向到内存流，避免写死 System.out 无法断言）。 */
    public ConsoleTraceSink(boolean verbose, PrintStream out) {
        this.verbose = verbose;
        this.out = out == null ? System.out : out;
    }

    @Override
    public void accept(TraceEvent event) {
        out.println("[trace] " + event.render());
        if (verbose && !event.data().isEmpty()) {
            out.println("        " + event.data());
        }
    }
}
