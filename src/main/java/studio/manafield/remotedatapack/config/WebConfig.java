package studio.manafield.remotedatapack.config;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;

/** Validated application configuration, independent of server/world state. */
public record WebConfig(boolean enabled, InetAddress bind, int port, String publicUrl,
                        int workers, int queueCapacity, int responseTimeoutSeconds) {
    public static WebConfig from(Map<String, Object> values) {
        Object enabled = values.getOrDefault("web.enabled", true);
        if (!(enabled instanceof Boolean)) throw invalid("web.enabled must be a boolean");
        String bindText = string(values, "web.bind", "127.0.0.1");
        InetAddress bind;
        try {
            bind = InetAddress.ofLiteral(bindText);
        } catch (IllegalArgumentException e) {
            throw invalid("web.bind must be a numeric loopback address");
        }
        // Only loopback listeners are allowed; remote access requires an HTTPS proxy.
        if (!bind.isLoopbackAddress()) throw invalid("web.bind must be a loopback address (127.0.0.1 or ::1)");
        String publicUrl = string(values, "web.public-url", "");
        if (!publicUrl.isEmpty()) {
            try {
                URI uri = URI.create(publicUrl);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null
                        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                        || uri.getPort() == 0 || uri.getPort() > 65535) {
                    throw invalid("web.public-url must be an HTTPS origin without credentials, query or path");
                }
                publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
            } catch (IllegalArgumentException e) {
                throw invalid("web.public-url must be an HTTPS origin without credentials, query or path");
            }
        }
        return new WebConfig((Boolean) enabled, bind, integer(values, "web.port", 8765, 1, 65535), publicUrl,
                integer(values, "web.workers", 4, 1, 16), integer(values, "web.queue-capacity", 32, 1, 256),
                integer(values, "web.response-timeout-seconds", 10, 1, 60));
    }

    private static String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.getOrDefault(key, fallback);
        if (!(value instanceof String text)) throw invalid(key + " must be a string");
        return text.strip();
    }

    private static int integer(Map<String, Object> values, String key, int fallback, int min, int max) {
        Object value = values.getOrDefault(key, fallback);
        if (!(value instanceof Integer || value instanceof Long)) throw invalid(key + " must be an integer");
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw invalid(key + " must be between " + min + " and " + max);
        return (int) number;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
