package studio.manafield.remotedatapack.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Memory-only state. Production invokes every operation on Paper's main thread.
 * Synchronized transitions also give tests and shutdown a precise linearization point. */
public final class AccessManager implements AutoCloseable {
    private static final long LINK_TTL = Duration.ofSeconds(60).toNanos();
    private static final int MAX_CONNECTIONS = 4096;
    private final SecureRandom random;
    private final LongSupplier time;
    private final Predicate<UUID> currentlyAllowed;
    private final long idleTtl, sessionTtl;
    private final Map<UUID, Connection> connections = new HashMap<>();
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private boolean closed;

    public AccessManager(AuthConfig config, Predicate<UUID> currentlyAllowed) {
        this(config, currentlyAllowed, System::nanoTime, new SecureRandom());
    }
    public AccessManager(AuthConfig config, Predicate<UUID> currentlyAllowed, LongSupplier time, SecureRandom random) {
        this.currentlyAllowed = currentlyAllowed; this.time = time; this.random = random;
        idleTtl = Duration.ofMinutes(config.idleMinutes()).toNanos();
        sessionTtl = Duration.ofMinutes(config.sessionMinutes()).toNanos();
    }
    public synchronized String join(UUID id, String name) { return join(id, name, null); }
    public synchronized String join(UUID id, String name, String skinUrl) {
        live(); revoke(id);
        if (!connections.containsKey(id) && connections.size() >= MAX_CONNECTIONS) throw new AuthFailure(503, "capacity_exceeded");
        String connectionId = secret();
        connections.put(id, new Connection(id, connectionId, name, studio.manafield.remotedatapack.avatar.SkinFaces.source(skinUrl)));
        return connectionId;
    }
    public synchronized void quit(UUID id, String connectionId) {
        Connection current = connections.get(id);
        if (current != null && current.connectionId.equals(connectionId)) { revoke(id); connections.remove(id); }
    }
    public synchronized String issue(UUID id) {
        live(); removeExpired(id);
        Connection connection = connections.get(id);
        if (connection == null) throw new AuthFailure(401, "game_disconnected");
        validate(connection);
        links.values().removeIf(link -> link.connection.id.equals(id));
        String token = secret();
        links.put(hash(token), new Link(connection, time.getAsLong() + LINK_TTL));
        return token;
    }
    public synchronized Granted redeem(String token) {
        live();
        Link link = links.remove(key(token)); // Consumption precedes validation, exactly once.
        if (link == null || time.getAsLong() >= link.until) throw new AuthFailure(401, "invalid_or_expired_link");
        validate(link.connection);
        removeSessions(session -> session.connection.id.equals(link.connection.id));
        String sessionId = secret(), csrf = secret();
        long now = time.getAsLong();
        Session session = new Session(link.connection, csrf, now + sessionTtl, now);
        sessions.put(hash(sessionId), session);
        return new Granted(sessionId, view(session));
    }
    public synchronized View session(String sessionId) {
        Session session = find(sessionId);
        session.lastAccess = time.getAsLong();
        return view(session);
    }
    public synchronized void logout(String sessionId, String csrf) {
        Session session = find(sessionId);
        if (csrf == null || !MessageDigest.isEqual(session.csrf.getBytes(StandardCharsets.US_ASCII), csrf.getBytes(StandardCharsets.US_ASCII))) {
            throw new AuthFailure(403, "invalid_csrf");
        }
        session.valid = false; sessions.remove(key(sessionId));
    }
    public synchronized void revoke(UUID id) {
        links.values().removeIf(link -> link.connection.id.equals(id));
        removeSessions(session -> session.connection.id.equals(id));
    }
    public synchronized String status(UUID id) {
        live(); removeExpired(id);
        return "링크 " + links.values().stream().filter(x -> x.connection.id.equals(id)).count()
                + "개 / 웹 세션 " + sessions.values().stream().filter(x -> x.connection.id.equals(id)).count() + "개";
    }
    /** Lazy expiry for the requesting account; no scheduled permission scan. */
    private void removeExpired(UUID id) {
        long now = time.getAsLong();
        links.values().removeIf(link -> link.connection.id.equals(id) && now >= link.until);
        removeSessions(session -> session.connection.id.equals(id) && expired(session, now));
    }
    private void removeSessions(Predicate<Session> match) {
        sessions.values().removeIf(session -> { if (!match.test(session)) return false; session.valid = false; return true; });
    }
    /** Called on main thread; only this method evaluates Bukkit authority. */
    public synchronized Lease readLease(String sessionId) { return new Lease(find(sessionId)); }
    public synchronized Lease lease(String sessionId, String csrf) {
        Session session = find(sessionId);
        if (csrf == null || !MessageDigest.isEqual(session.csrf.getBytes(StandardCharsets.US_ASCII), csrf.getBytes(StandardCharsets.US_ASCII)))
            throw new AuthFailure(403, "invalid_csrf");
        session.lastAccess = time.getAsLong(); return new Lease(session);
    }
    public synchronized boolean owns(String sessionId, Lease lease) { return find(sessionId) == lease.session; }
    /** Re-check live player permission on main immediately before a commit reservation. */
    public synchronized void validate(Lease lease) {
        check(lease); validate(lease.session.connection);
    }
    /** Worker-safe cancellation check: no Bukkit calls. */
    public synchronized void check(Lease lease) {
        live(); Session session = lease.session;
        if (lease.cancelled || !session.valid || expired(session, time.getAsLong())
                || !session.connection.equals(connections.get(session.connection.id))) throw new AuthFailure(401, "operation_cancelled");
    }
    /** Linearizes commit against quit/revoke, without doing I/O under the monitor.
     * Once reserved, the one atomic filesystem syscall is already in flight and cannot be recalled.
     * No main thread ever waits for filesystem completion. */
    public synchronized void reserveCommit(Lease lease) {
        check(lease); if (lease.committed) throw new AuthFailure(409, "commit_already_reserved"); lease.committed = true;
    }
    public synchronized String skin(Lease lease) { check(lease); return lease.session.connection.skinUrl; }
    public synchronized void cancel(Lease lease) { lease.cancelled = true; }
    public static final class Lease {
        private final Session session; private boolean cancelled, committed;
        private Lease(Session session) { this.session = session; }
        public UUID playerId() { return session.connection.id; }
        @Override public String toString() { return "Lease[redacted]"; }
    }
    private Session find(String id) {
        live(); String key = key(id); Session session = sessions.get(key);
        if (session == null) throw new AuthFailure(401, "session_ended");
        if (expired(session, time.getAsLong())) { session.valid = false; sessions.remove(key); throw new AuthFailure(401, "session_expired"); }
        validate(session.connection);
        return session;
    }
    private void validate(Connection connection) {
        if (!connection.equals(connections.get(connection.id))) throw new AuthFailure(401, "game_disconnected");
        if (!currentlyAllowed.test(connection.id)) { revoke(connection.id); throw new AuthFailure(403, "permission_revoked"); }
    }
    private boolean expired(Session session, long now) { return now >= session.until || now - session.lastAccess >= idleTtl; }
    private View view(Session session) {
        return new View(session.connection.id, session.connection.name, session.csrf,
                Math.max(0, (session.until - time.getAsLong()) / 1_000_000_000));
    }
    private void live() { if (closed) throw new AuthFailure(503, "auth_unavailable"); }
    private String secret() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String key(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw new AuthFailure(401, "invalid_credential");
        return hash(secret);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public synchronized void close() { closed = true; links.clear(); removeSessions(session -> true); connections.clear(); }
    private record Connection(UUID id, String connectionId, String name, String skinUrl) { }
    private record Link(Connection connection, long until) { }
    private static final class Session {
        boolean valid = true;
        final Connection connection; final String csrf; final long until; long lastAccess;
        Session(Connection connection, String csrf, long until, long lastAccess) {
            this.connection = connection; this.csrf = csrf; this.until = until; this.lastAccess = lastAccess;
        }
    }
    public record View(UUID playerId, String playerName, String csrf, long expiresInSeconds) {
        @Override public String toString() { return "View[redacted]"; }
    }
    public record Granted(String sessionId, View view) {
        @Override public String toString() { return "Granted[redacted]"; }
    }
}
