package studio.manafield.remotedatapack.auth;

import studio.manafield.remotedatapack.config.WebConfig;
import java.util.Map;
import java.util.Locale;
import java.net.URI;

public record AuthConfig(boolean localDevelopment, boolean trustProxyIdentity, int idleMinutes, int sessionMinutes) {
    public static AuthConfig from(Map<String, Object> values, WebConfig web) {
        boolean local = bool(values, "web.local-development");
        if (local && !web.publicUrl().isEmpty()) throw new IllegalArgumentException("Local development cannot use web.public-url");
        if (number(values, "auth.link-ttl-seconds", 60, 60, 60) != 60
                || number(values, "auth.max-sessions-per-player", 1, 1, 1) != 1) throw new IllegalArgumentException("Unsupported auth policy");
        number(values, "ui.session-poll-seconds", 5, 5, 5);
        return new AuthConfig(local, bool(values, "auth.trust-proxy-identity"),
                number(values, "auth.idle-timeout-minutes", 30, 1, 1440),
                number(values, "auth.session-ttl-minutes", 30, 1, 1440));
    }
    private static boolean bool(Map<String, Object> values, String key) {
        Object value = values.getOrDefault(key, false);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(key + " must be boolean");
        return result;
    }
    private static int number(Map<String, Object> values, String key, int fallback, int min, int max) {
        Object value = values.getOrDefault(key, fallback);
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < min
                || ((Number) value).longValue() > max) throw new IllegalArgumentException(key + " is outside the supported range");
        return ((Number) value).intValue();
    }
    public String origin(WebConfig web, int actualPort) {
        if (!localDevelopment) {
            if (web.publicUrl().isEmpty()) return "";
            URI uri = URI.create(web.publicUrl());
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
        }
        return "http://" + (web.bind().getAddress().length == 16 ? "[::1]" : web.bind().getHostAddress()) + ":" + actualPort;
    }
    public String cookieName() { return localDevelopment ? "remotedatapack_dev_session" : "__Host-remotedatapack_session"; }
    public String cookie(String value, boolean clear) {
        return cookieName() + "=" + value + "; Path=/; HttpOnly; SameSite=Strict"
                + (localDevelopment ? "" : "; Secure") + (clear ? "; Max-Age=0" : "");
    }
}
