package com.miniagent.session;

import com.miniagent.error.SessionAccessException;
import com.miniagent.llm.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话管理：多窗口隔离、归属校验、并发安全。 */
class SessionManagerTest {

    @Test
    @DisplayName("同一用户的多个窗口彼此独立：历史与工作记忆都不共享")
    void sessionsAreIsolated() {
        SessionManager manager = new SessionManager();
        Session window1 = manager.create("A", "w1", "窗口1");
        Session window2 = manager.create("A", "w2", "窗口2");

        window1.append(Message.user("查天气"));
        window1.memory().addTodo("给张总发周报");

        assertEquals(1, window1.historySize());
        assertEquals(0, window2.historySize());
        assertEquals(1, window1.memory().todos().size());
        assertEquals(0, window2.memory().todos().size());
        assertNotEquals(window1.id(), window2.id());
    }

    @Test
    @DisplayName("getOrCreate 幂等：同一 id 反复取到同一个对象，可随时接着聊")
    void getOrCreateIsIdempotent() {
        SessionManager manager = new SessionManager();
        Session first = manager.getOrCreate("A", "w1", "窗口1");
        first.append(Message.user("第一句"));
        Session again = manager.getOrCreate("A", "w1", "忽略这个标题");
        again.append(Message.user("第二句"));

        assertEquals(2, again.historySize());
        assertEquals("窗口1", again.title());
        assertEquals(1, manager.size());
    }

    @Test
    @DisplayName("sessionId 为空时新建独立会话")
    void blankSessionIdCreatesNew() {
        SessionManager manager = new SessionManager();
        Session a = manager.getOrCreate("A", null, "t");
        Session b = manager.getOrCreate("A", "  ", "t");
        assertNotEquals(a.id(), b.id());
        assertEquals(2, manager.size());
    }

    @Test
    @DisplayName("跨用户访问被拒绝（多用户隔离防线）")
    void rejectsCrossUserAccess() {
        SessionManager manager = new SessionManager();
        manager.create("A", "w1", "A 的窗口");
        assertThrows(SessionAccessException.class, () -> manager.require("B", "w1"));
        assertEquals(0, manager.list("B").size());
    }

    @Test
    @DisplayName("重复创建同一 id 会失败，避免覆盖已有会话")
    void rejectsDuplicateCreate() {
        SessionManager manager = new SessionManager();
        manager.create("A", "w1", "窗口1");
        assertThrows(SessionAccessException.class, () -> manager.create("A", "w1", "重复"));
    }

    @Test
    @DisplayName("list 只返回该用户的会话，删除后不再出现")
    void listsAndDeletesPerUser() {
        SessionManager manager = new SessionManager();
        manager.create("A", "w1", "1");
        manager.create("A", "w2", "2");
        manager.create("B", "w3", "3");

        assertEquals(2, manager.list("A").size());
        assertEquals(1, manager.list("B").size());
        assertEquals(List.of("w1", "w2"), manager.list("A").stream().map(Session::id).toList());

        manager.delete("A", "w1");
        assertEquals(List.of("w2"), manager.list("A").stream().map(Session::id).toList());
        assertThrows(SessionAccessException.class, () -> manager.require("A", "w1"));
    }

    @Test
    @DisplayName("同一会话被并发写入时历史不串行、不丢失")
    void concurrentAppendsAreConsistent() throws Exception {
        Session session = new SessionManager().create("A", "w1", "并发");
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    session.append(Message.user("消息 " + counter.incrementAndGet()));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        List<Message> history = session.history();
        assertEquals(threads * perThread, history.size());
        // 流水号必须唯一且严格递增
        for (int i = 0; i < history.size(); i++) {
            assertEquals(i + 1, history.get(i).seq());
        }
    }

    @Test
    @DisplayName("工作记忆：待办全生命周期 + 事实上限淘汰")
    void workingMemoryBehaviour() {
        WorkingMemory memory = new WorkingMemory();
        assertTrue(memory.isEmpty());

        TodoItem first = memory.addTodo("发周报");
        memory.addTodo("买咖啡");
        assertEquals(1, first.id());
        assertTrue(memory.completeTodo(2).isPresent());
        assertTrue(memory.completeTodo(99).isEmpty());
        assertEquals(1, memory.clearCompleted());
        assertEquals(1, memory.todos().size());
        assertTrue(memory.render().contains("发周报"));
        assertTrue(memory.render().contains("未完成 1 项"));

        for (int i = 0; i < 30; i++) {
            memory.putFact("k" + i, "v" + i);
        }
        assertEquals(20, memory.facts().size());
        assertTrue(memory.facts().containsKey("k29"));
        assertTrue(!memory.facts().containsKey("k0"));

        memory.clearAll();
        assertTrue(memory.todos().isEmpty());
    }

    @Test
    @DisplayName("会话摘要与压缩位点可增量更新")
    void summaryBookkeeping() {
        Session session = new SessionManager().create("A", "w1", "t");
        assertEquals(0, session.summarizedUpTo());
        session.updateSummary("用户想查天气", 6);
        assertEquals("用户想查天气", session.summary());
        assertEquals(6, session.summarizedUpTo());
    }
}
