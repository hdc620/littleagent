package com.littleagent.session;

import com.littleagent.error.SessionAccessException;
import com.littleagent.trace.Tracer;
import com.littleagent.trace.TraceTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 会话管理器：负责 session 的创建、查找与<b>归属校验</b>。
 *
 * <p>三个关键设计：
 * <ol>
 *   <li>session 之间完全隔离：各自持有历史、工作记忆、trace；</li>
 *   <li>session 归属某个 userId，跨用户访问直接抛 {@link SessionAccessException}；</li>
 *   <li>session 可以被「挂起后随时继续」：对象常驻内存，下一轮直接续写历史。</li>
 * </ol>
 *
 * <p>存储是可替换的：当前是进程内 {@link ConcurrentHashMap}（够用且便于测试）；
 * 换成 Redis / DB 只需要实现同样的方法签名。
 */
public final class SessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<String>> byUser = new ConcurrentHashMap<>();
    private final Function<String, Tracer> tracerFactory;

    public SessionManager() {
        this(sessionId -> Tracer.noop());
    }

    public SessionManager(Function<String, Tracer> tracerFactory) {
        this.tracerFactory = tracerFactory == null ? sessionId -> Tracer.noop() : tracerFactory;
    }

    /** 新建会话。 */
    public Session create(String userId, String title) {
        return create(userId, generateId(), title);
    }

    /** 新建会话（指定 id，便于 CLI 用 w1/w2 这种可读 id）。 */
    public Session create(String userId, String sessionId, String title) {
        String uid = requireUser(userId);
        Session session = new Session(sessionId, uid, title, tracerFactory.apply(sessionId));
        Session previous = sessions.putIfAbsent(sessionId, session);
        if (previous != null) {
            throw new SessionAccessException("sessionId 已存在: " + sessionId);
        }
        byUser.computeIfAbsent(uid, k -> Collections.synchronizedList(new ArrayList<>())).add(sessionId);
        session.tracer().event(TraceTypes.SESSION_CREATED, "创建会话 " + sessionId,
                Tracer.data("userId", uid, "title", title));
        return session;
    }

    /** 取已有会话；不存在则创建（保证幂等，CLI/HTTP 都能用）。 */
    public Session getOrCreate(String userId, String sessionId, String title) {
        if (sessionId == null || sessionId.isBlank()) {
            return create(userId, title);
        }
        Session existing = sessions.get(sessionId);
        if (existing != null) {
            requireOwner(userId, existing);
            return existing;
        }
        synchronized (this) {
            Session again = sessions.get(sessionId);
            if (again != null) {
                requireOwner(userId, again);
                return again;
            }
            return create(userId, sessionId, title);
        }
    }

    /** 严格获取：不存在或不属于该用户都抛异常。 */
    public Session require(String userId, String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionAccessException("会话不存在: " + sessionId);
        }
        requireOwner(userId, session);
        return session;
    }

    public Optional<Session> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** 某用户的全部会话（创建顺序）。 */
    public List<Session> list(String userId) {
        List<String> ids = byUser.get(userId);
        if (ids == null) {
            return List.of();
        }
        List<Session> out = new ArrayList<>();
        synchronized (ids) {
            for (String id : ids) {
                Session session = sessions.get(id);
                if (session != null) {
                    out.add(session);
                }
            }
        }
        return out;
    }

    public int size() {
        return sessions.size();
    }

    public void delete(String userId, String sessionId) {
        Session session = require(userId, sessionId);
        sessions.remove(session.id());
        List<String> ids = byUser.get(userId);
        if (ids != null) {
            synchronized (ids) {
                ids.remove(sessionId);
            }
        }
    }

    /** 用户 -> 会话数，用于观测/测试。 */
    public Map<String, Integer> sessionCountByUser() {
        Map<String, Integer> out = new LinkedHashMap<>();
        byUser.forEach((user, ids) -> out.put(user, ids.size()));
        return out;
    }

    private void requireOwner(String userId, Session session) {
        if (!session.userId().equals(requireUser(userId))) {
            throw new SessionAccessException(
                    "用户 " + userId + " 无权访问会话 " + session.id() + "（属于 " + session.userId() + "）");
        }
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new SessionAccessException("userId 不能为空");
        }
        return userId.strip();
    }

    private static String generateId() {
        return "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
