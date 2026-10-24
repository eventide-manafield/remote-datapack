package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.HttpExchange;
import studio.manafield.remotedatapack.auth.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** No Bukkit API, filesystem access, credential logging or proxy-header trust. */
public final class SessionHttpHandler implements AutoCloseable {
    private final studio.manafield.remotedatapack.avatar.SkinFaces faces = new studio.manafield.remotedatapack.avatar.SkinFaces();
    private static final Pattern TOKEN_JSON = Pattern.compile("\\s*\\{\\s*\"token\"\\s*:\\s*\"([A-Za-z0-9_-]{43})\"\\s*}\\s*");
    private final AuthConfig config;
    private final String origin;
    private final AccessManager access;
    private final MainThreadGate gate;
    private double redeemBudget = 20;
    private long budgetTime = System.nanoTime();

    public SessionHttpHandler(AuthConfig config, String origin, AccessManager access, MainThreadGate gate) {
        this.config = config; this.origin = origin; this.access = access; this.gate = gate;
    }
    public String origin() { return origin; }
    public boolean matches(String path) {
        return path.equals("/api/session/avatar") || path.equals("/api/session") || path.equals("/api/session/redeem") || path.equals("/api/session/logout");
    }
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (path.equals("/api/session/avatar")) { avatar(exchange); return; }
        boolean redeem = path.equals("/api/session/redeem"), read = path.equals("/api/session");
        try {
            if (exchange.getRequestURI().getRawQuery() != null) throw new AuthFailure(400, "query_not_allowed");
            if (!exchange.getRequestMethod().equals(read ? "GET" : "POST")) {
                exchange.getResponseHeaders().set("Allow", read ? "GET" : "POST");
                throw new AuthFailure(405, "method_not_allowed");
            }
            verifyOrigin(exchange, !read);
            if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")) throw new AuthFailure(413, "body_too_large");
            if (redeem) {
                if (!admitRedeem()) {
                    exchange.getResponseHeaders().set("Retry-After", "1");
                    throw new AuthFailure(429, "too_many_attempts");
                }
                String type = single(exchange, "Content-Type");
                if (type == null || !type.matches("(?i)application/json(?:;\\s*charset=utf-8)?")) throw new AuthFailure(415, "json_required");
                int length = contentLength(exchange);
                if (length < 1) throw new AuthFailure(411, "content_length_required");
                if (length > 256) throw new AuthFailure(413, "body_too_large");
                byte[] body = exchange.getRequestBody().readNBytes(length);
                if (body.length != length) throw new AuthFailure(400, "invalid_body");
                var matcher = TOKEN_JSON.matcher(new String(body, StandardCharsets.UTF_8));
                if (!matcher.matches()) throw new AuthFailure(400, "invalid_body");
                String token = matcher.group(1);
                AccessManager.Granted granted = gate.call(() -> access.redeem(token));
                exchange.getResponseHeaders().set("Set-Cookie", config.cookie(granted.sessionId(), false));
                json(exchange, 200, view(granted.view()));
            } else {
                if (contentLength(exchange) > 0) throw new AuthFailure(413, "request_body_not_supported");
                String sessionId = cookie(exchange);
                if (read) json(exchange, 200, view(gate.call(() -> access.session(sessionId))));
                else {
                    String csrf = single(exchange, "X-CSRF-Token");
                    if (csrf == null || !csrf.matches("[A-Za-z0-9_-]{43}")) throw new AuthFailure(403, "invalid_csrf");
                    gate.call(() -> { access.logout(sessionId, csrf); return true; });
                    exchange.getResponseHeaders().set("Set-Cookie", config.cookie("", true));
                    json(exchange, 200, "{\"status\":\"signed_out\"}");
                }
            }
        } catch (AuthFailure failure) {
            if (!redeem && (failure.status() == 401 || failure.getMessage().equals("permission_revoked"))) {
                exchange.getResponseHeaders().set("Set-Cookie", config.cookie("", true));
            }
            json(exchange, failure.status(), "{\"error\":\"" + failure.getMessage() + "\"}");
        }
    }
    private void avatar(HttpExchange exchange) throws IOException {
        try {
            String sessionId = authorizeRead(exchange);
            AccessManager.Lease lease = gate.call(() -> access.readLease(sessionId));
            byte[] png = faces.face(access.skin(lease));
            // Download can race with quit, revocation, expiry or shutdown. Revalidate before responding.
            gate.call(() -> { access.validate(lease); return true; });
            if (png == null) throw new AuthFailure(404, "avatar_unavailable");
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
        } catch (AuthFailure failure) { failure(exchange, failure); }
    }
    @Override public void close() { faces.close(); }
    private void verifyOrigin(HttpExchange exchange, boolean required) {
        String sent = single(exchange, "Origin");
        String fetchSite = single(exchange, "Sec-Fetch-Site");
        if ((required && sent == null) || (sent != null && !origin.equals(sent))
                || (fetchSite != null && !fetchSite.equals("same-origin") && !(fetchSite.equals("none") && !required))) {
            throw new AuthFailure(403, "invalid_origin");
        }
    }
    static String single(HttpExchange exchange, String key) {
        List<String> values = exchange.getRequestHeaders().get(key);
        if (values == null) return null;
        if (values.size() != 1) throw new AuthFailure(400, "ambiguous_header");
        return values.getFirst();
    }
    static int contentLength(HttpExchange exchange) {
        String value = single(exchange, "Content-Length");
        if (value == null) return 0;
        try {
            int length = Integer.parseInt(value);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException e) { throw new AuthFailure(413, "body_too_large"); }
    }
    private String cookie(HttpExchange exchange) {
        String found = null;
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.strip().split("=", 2);
                if (pair[0].equals(config.cookieName())) {
                    if (found != null || pair.length != 2) throw new AuthFailure(401, "invalid_credential");
                    found = pair[1];
                }
            }
        }
        return found;
    }
    private synchronized boolean admitRedeem() {
        long now = System.nanoTime();
        redeemBudget = Math.min(20, redeemBudget + (now - budgetTime) / 1_000_000_000.0 * 5);
        budgetTime = now;
        if (redeemBudget < 1) return false;
        redeemBudget -= 1;
        return true;
    }
    private java.util.function.Supplier<String> applyStatus;
    public void applyStatus(java.util.function.Supplier<String> source) {applyStatus=source;}
    private String view(AccessManager.View view) {
        return "{\"playerId\":\"" + view.playerId() + "\",\"playerName\":" + Json.quote(view.playerName())
                + ",\"csrf\":\"" + view.csrf() + "\",\"expiresInSeconds\":" + view.expiresInSeconds() + (applyStatus==null?"":",\"apply\":"+applyStatus.get()) + "}";
    }
    String authorizeRead(HttpExchange exchange) {
        if (exchange.getRequestURI().getRawQuery() != null) throw new AuthFailure(400, "query_not_allowed");
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            throw new AuthFailure(405, "method_not_allowed");
        }
        verifyOrigin(exchange, false);
        if (exchange.getRequestHeaders().containsKey("Transfer-Encoding") || contentLength(exchange) > 0) {
            throw new AuthFailure(413, "request_body_not_supported");
        }
        return cookie(exchange);
    }
    String authorizeWrite(HttpExchange exchange, String method) {
        if (exchange.getRequestURI().getRawQuery() != null) throw new AuthFailure(400, "query_not_allowed");
        if (!exchange.getRequestMethod().equals(method)) {
            exchange.getResponseHeaders().set("Allow", method); throw new AuthFailure(405, "method_not_allowed");
        }
        verifyOrigin(exchange, true);
        if (exchange.getRequestHeaders().containsKey("Transfer-Encoding") || exchange.getRequestHeaders().containsKey("Content-Encoding"))
            throw new AuthFailure(413, "unsupported_encoding");
        return cookie(exchange);
    }
    void failure(HttpExchange exchange, AuthFailure failure) throws IOException {
        if (failure.status() == 401 || failure.getMessage().equals("permission_revoked")) {
            exchange.getResponseHeaders().set("Set-Cookie", config.cookie("", true));
        }
        json(exchange, failure.status(), "{\"error\":" + Json.quote(failure.getMessage()) + "}");
    }
    static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
