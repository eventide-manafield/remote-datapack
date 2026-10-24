package studio.manafield.remotedatapack.http;

/** JDK pre-handler limits are process-wide and must be supplied at JVM startup. */
public final class HttpServerLimits {
    private HttpServerLimits() { }

    public static void validate() {
        bounded("sun.net.httpserver.maxReqTime", 60);
        bounded("sun.net.httpserver.maxRspTime", 60);
        bounded("jdk.httpserver.maxConnections", 256);
        bounded("sun.net.httpserver.maxReqHeaders", 100);
        bounded("sun.net.httpserver.maxReqHeaderSize", 32768);
    }

    private static void bounded(String property, int max) {
        String raw = System.getProperty(property, "");
        try {
            long value = Long.parseLong(raw);
            if (value > 0 && value <= max) return;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Set JVM startup option -D" + property + "=<1.." + max
                + "> before -jar; see README. Web listener remains disabled.");
    }
}
