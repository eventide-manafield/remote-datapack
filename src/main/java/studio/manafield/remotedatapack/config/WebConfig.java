package studio.manafield.remotedatapack.config;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;

/** Validated application configuration, independent of server/world state. */
public record WebConfig(boolean enabled, InetAddress bind, int port, String publicUrl,
                        int workers, int queueCapacity, int responseTimeoutSeconds, boolean directHttp) {
    public static WebConfig from(Map<String, Object> values) {
        Object enabled = values.getOrDefault("web.enabled", true);
        if (!(enabled instanceof Boolean)) throw invalid("web.enabled must be a boolean");
        // Missing mode retains the HTTPS-only policy of existing installations.
        String mode = string(values, "web.mode", "https-proxy");
        if (!mode.equals("direct-http") && !mode.equals("https-proxy"))
            throw invalid("web.mode must be direct-http or https-proxy");
        boolean direct = mode.equals("direct-http");
        String bindText = string(values, "web.bind", direct ? "0.0.0.0" : "127.0.0.1");
        InetAddress bind;
        try {
            // Java 21: validate numeric-only syntax before calling the legacy address parser.
            if (!bindText.contains(":")) {
                String[] parts = bindText.split("\\.", -1);
                if (parts.length != 4) throw new IllegalArgumentException();
                byte[] address = new byte[4];
                for (int i = 0; i < 4; i++) {
                    if (!parts[i].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[i]) > 255)
                        throw new IllegalArgumentException();
                    address[i] = (byte) Integer.parseInt(parts[i]);
                }
                bind = InetAddress.getByAddress(address);
            } else {
                if (!bindText.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException();
                // A colon forces IPv6 literal parsing; hostnames and scoped addresses never reach DNS.
                bind = InetAddress.getByName(bindText);
            }
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            throw invalid("web.bind must be a numeric IP address");
        }
        // External listeners require the explicit direct HTTP mode.
        if (bind.isMulticastAddress() || bindText.contains("%")) throw invalid("Invalid web.bind address");
        if (!direct && !bind.isLoopbackAddress()) throw invalid("web.bind must be a loopback address (127.0.0.1 or ::1)");
        String publicUrl = string(values, "web.public-url", "");
        if (!publicUrl.isEmpty()) {
            try {
                URI uri = URI.create(publicUrl);
                if (!(direct ? "http" : "https").equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null
                        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                        || uri.getPort() == 0 || uri.getPort() > 65535
                        || uri.getRawAuthority().endsWith(":") || uri.getHost().contains("%")
                        || uri.getHost().equals("0.0.0.0") || uri.getHost().equals("[::]")
                        || uri.getHost().equals("[0:0:0:0:0:0:0:0]")) {
                    throw invalid("web.public-url must be an " + (direct ? "HTTP" : "HTTPS") + " origin without credentials, query or path");
                }
                publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
            } catch (IllegalArgumentException e) {
                throw invalid("web.public-url must be an " + (direct ? "HTTP" : "HTTPS") + " origin without credentials, query or path");
            }
        }
        return new WebConfig((Boolean) enabled, bind, integer(values, "web.port", 13532, 1, 65535), publicUrl,
                integer(values, "web.workers", 4, 1, 16), integer(values, "web.queue-capacity", 32, 1, 256),
                integer(values, "web.response-timeout-seconds", 10, 1, 60), direct);
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
