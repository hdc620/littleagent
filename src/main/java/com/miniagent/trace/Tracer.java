package com.miniagent.trace;

import com.miniagent.util.Texts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 会话级 trace 记录器：内存保留最近事件 + 扇出到若干 {@link TraceSink}。
 *
 * <p>用法上它是「会话的一部分」：一个 session 一个 Tracer，工具、上下文、循环都往里写，
 * 这样一次问答的完整因果链可以按 sessionId 直接重放。
 */
public final class Tracer implements AutoCloseable {

    private static final int MAX_IN_MEMORY_EVENTS = 2000;
    private static final Tracer NOOP = new Tracer("noop", List.of(), false);

    private final String sessionId;
    private final List<TraceSink> sinks;
    private final boolean enabled;
    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    private Tracer(String sessionId, List<TraceSink> sinks, boolean enabled) {
        this.sessionId = sessionId;
        this.sinks = List.copyOf(sinks);
        this.enabled = enabled;
    }

    /** 什么都不做的 Tracer，用于测试或显式关掉观测。 */
    public static Tracer noop() {
        return NOOP;
    }

    public static Tracer of(String sessionId, TraceSink... sinks) {
        return new Tracer(sessionId, List.of(sinks), true);
    }

    public static Builder builder(String sessionId) {
        return new Builder(sessionId);
    }

    public String sessionId() {
        return sessionId;
    }

    public boolean enabled() {
        return enabled;
    }

    public TraceEvent event(String type, String summary, Map<String, Object> data) {
        if (!enabled) {
            return null;
        }
        TraceEvent event = new TraceEvent(seq.incrementAndGet(), Instant.now(), sessionId, type,
                summary, data, 0L);
        publish(event);
        return event;
    }

    public TraceEvent event(String type, String summary) {
        return event(type, summary, Map.of());
    }

    public TraceEvent event(String type, String summary, Object... keyValues) {
        return event(type, summary, data(keyValues));
    }

    /** 记录一次耗时操作；异常也会被记录后原样抛出。 */
    public <T> T timed(String type, String summary, Supplier<T> body) {
        return timed(type, summary, Map.of(), body);
    }

    public <T> T timed(String type, String summary, Map<String, Object> extra, Supplier<T> body) {
        long start = System.nanoTime();
        try {
            T result = body.get();
            if (enabled) {
                Map<String, Object> data = new LinkedHashMap<>(extra);
                data.put("ok", true);
                if (result != null) {
                    data.put("result", Texts.oneLine(String.valueOf(result), 160));
                }
                publish(new TraceEvent(seq.incrementAndGet(), Instant.now(), sessionId, type, summary,
                        data, elapsedMs(start)));
            }
            return result;
        } catch (RuntimeException e) {
            if (enabled) {
                Map<String, Object> data = new LinkedHashMap<>(extra);
                data.put("ok", false);
                data.put("error", e.getClass().getSimpleName() + ": " + Texts.oneLine(e.getMessage(), 200));
                publish(new TraceEvent(seq.incrementAndGet(), Instant.now(), sessionId, type, summary,
                        data, elapsedMs(start)));
            }
            throw e;
        }
    }

    /** 记录异常（不抛出）。 */
    public void failure(String type, String summary, Throwable error) {
        if (!enabled) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", error == null ? "unknown" : error.getClass().getName());
        data.put("error", error == null ? "" : Texts.oneLine(error.getMessage(), 300));
        event(type, summary, data);
    }

    public List<TraceEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public void clear() {
        events.clear();
    }

    /** 人类可读的时间线，CLI 的 {@code /trace} 用它。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== trace of session ").append(sessionId).append(" (").append(events.size()).append(" events) ===\n");
        for (TraceEvent e : events) {
            sb.append(e.render()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void close() {
        // 目前 sink 都是即时写，无需 flush；保留 AutoCloseable 以便未来换缓冲实现。
    }

    private void publish(TraceEvent event) {
        events.add(event);
        if (events.size() > MAX_IN_MEMORY_EVENTS) {
            events.remove(0);
        }
        for (TraceSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (RuntimeException e) {
                // sink 自身故障不影响主流程
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 构造 data map 的语法糖，自动忽略 null key/value。 */
    public static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                map.put(String.valueOf(key), value);
            }
        }
        return map;
    }

    public static final class Builder {
        private final String sessionId;
        private final List<TraceSink> sinks = new ArrayList<>();

        private Builder(String sessionId) {
            this.sessionId = sessionId;
        }

        public Builder sink(TraceSink sink) {
            if (sink != null) {
                sinks.add(sink);
            }
            return this;
        }

        public Tracer build() {
            return new Tracer(sessionId, sinks, true);
        }
    }
}
