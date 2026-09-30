package com.littleagent.session;

import com.littleagent.util.TokenEstimator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工作记忆（working memory）：会话级的结构化状态。
 *
 * <p>与「消息历史」的区别：
 * <ul>
 *   <li>消息历史是流水账，会被压缩、会滑出窗口；</li>
 *   <li>工作记忆是<b>提炼后的关键事实</b>（待办、最近一次工具结论），体量小、永不滑出窗口，
 *       每轮都注入上下文。这就是「用户说了一半，下一轮 Agent 还记得待办」的实现方式。</li>
 * </ul>
 *
 * <p>容量有硬上限：facts 最多 20 条（LRU 淘汰最旧），todos 上限 50 条，避免工作记忆反过来把上下文撑爆。
 */
public final class WorkingMemory {

    private static final int MAX_FACTS = 20;
    private static final int MAX_TODOS = 50;

    private final Map<String, String> facts = new LinkedHashMap<>();
    private final List<TodoItem> todos = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int nextTodoId = 1;

    public void putFact(String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        lock.lock();
        try {
            facts.remove(key);
            facts.put(key, value);
            while (facts.size() > MAX_FACTS) {
                String oldest = facts.keySet().iterator().next();
                facts.remove(oldest);
            }
        } finally {
            lock.unlock();
        }
    }

    public String fact(String key) {
        lock.lock();
        try {
            return facts.get(key);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, String> facts() {
        lock.lock();
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        } finally {
            lock.unlock();
        }
    }

    public TodoItem addTodo(String text) {
        lock.lock();
        try {
            TodoItem item = new TodoItem(nextTodoId++, text == null ? "" : text.strip(), false, Instant.now());
            todos.add(item);
            while (todos.size() > MAX_TODOS) {
                todos.remove(0);
            }
            return item;
        } finally {
            lock.unlock();
        }
    }

    /** @return 被标记完成的条目；id 不存在时为空 */
    public Optional<TodoItem> completeTodo(int id) {
        lock.lock();
        try {
            for (int i = 0; i < todos.size(); i++) {
                TodoItem item = todos.get(i);
                if (item.id() == id) {
                    TodoItem done = item.complete();
                    todos.set(i, done);
                    return Optional.of(done);
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public boolean removeTodo(int id) {
        lock.lock();
        try {
            return todos.removeIf(t -> t.id() == id);
        } finally {
            lock.unlock();
        }
    }

    public int clearCompleted() {
        lock.lock();
        try {
            int before = todos.size();
            todos.removeIf(TodoItem::done);
            return before - todos.size();
        } finally {
            lock.unlock();
        }
    }

    public void clearAll() {
        lock.lock();
        try {
            todos.clear();
            nextTodoId = 1;
        } finally {
            lock.unlock();
        }
    }

    public List<TodoItem> todos() {
        lock.lock();
        try {
            return List.copyOf(todos);
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return facts.isEmpty() && todos.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /** 注入上下文时使用的紧凑文本表示。 */
    public String render() {
        lock.lock();
        try {
            if (facts.isEmpty() && todos.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!facts.isEmpty()) {
                sb.append("已知事实：");
                boolean first = true;
                for (Map.Entry<String, String> e : facts.entrySet()) {
                    sb.append(first ? "" : "；").append(e.getKey()).append('=').append(e.getValue());
                    first = false;
                }
                sb.append('\n');
            }
            if (!todos.isEmpty()) {
                long open = todos.stream().filter(t -> !t.done()).count();
                sb.append("待办清单（共 ").append(todos.size()).append(" 项，未完成 ").append(open).append(" 项）：\n");
                for (TodoItem item : todos) {
                    sb.append("  ").append(item.render()).append('\n');
                }
            }
            return sb.toString().strip();
        } finally {
            lock.unlock();
        }
    }

    public int estimatedTokens() {
        return TokenEstimator.estimate(render());
    }
}
