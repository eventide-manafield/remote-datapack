package studio.manafield.remotedatapack.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Loads a build-generated allowlist, never user paths or files on the server. */
public final class WebAssets {
    private static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    private final Map<String, Asset> assets;

    private WebAssets(Map<String, Asset> assets) { this.assets = Map.copyOf(assets); }

    /** Only operator-configured plain text is inserted; no executable configuration or extra endpoint. */
    public WebAssets withServerName(Object value) {
        if (!(value instanceof String name) || name.isBlank() || name.length() > 80
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ui.server-name must be 1..80 plain-text characters");
        }
        String escaped = name.strip().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
        Asset original = assets.get("/");
        String html = new String(original.bytes(), StandardCharsets.UTF_8)
                .replace("content=\"__REMOTE_DATAPACK_SERVER_NAME__\"", "content=\"" + escaped + "\"")
                .replace("<title>Remote Datapack</title>", "<title>Remote Datapack · " + escaped + "</title>");
        Asset index = new Asset(html.getBytes(StandardCharsets.UTF_8), original.contentType());
        Map<String, Asset> configured = new HashMap<>(assets);
        configured.put("/", index);
        configured.put("/index.html", index);
        return new WebAssets(configured);
    }

    public static WebAssets load(ClassLoader loader) throws IOException {
        Map<String, Asset> assets = new HashMap<>();
        String manifest = new String(read(loader, "web-assets.list", 32768), StandardCharsets.UTF_8);
        int total = 0;
        for (String name : manifest.lines().toList()) {
            if (!name.matches("(?:index\\.html|assets/[A-Za-z0-9_-]+\\.(?:js|css|svg|woff2))")) {
                throw new IOException("Invalid bundled asset manifest");
            }
            byte[] bytes = read(loader, "web/" + name, MAX_ASSET_BYTES);
            total += bytes.length;
            if (total > MAX_TOTAL_BYTES || assets.size() >= 128) throw new IOException("Web bundle exceeds resource limit");
            if (assets.put("/" + name, new Asset(bytes, contentType(name))) != null) throw new IOException("Duplicate web asset");
        }
        Asset index = assets.get("/index.html");
        if (index == null) throw new IOException("Missing bundled web/index.html");
        assets.put("/", index);
        return new WebAssets(assets);
    }

    private static byte[] read(ClassLoader loader, String name, int max) throws IOException {
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing bundled web resource");
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length > max) throw new IOException("Bundled resource exceeds size limit");
            return bytes;
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "font/woff2";
    }

    Asset get(String path) { return assets.get(path); }
    record Asset(byte[] bytes, String contentType) { }
}
