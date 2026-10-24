package studio.manafield.remotedatapack.avatar;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/** Worker-only bounded official-texture download and in-memory face cache. No player lookup or disk I/O. */
public final class SkinFaces implements AutoCloseable {
    static final int MAX_BYTES = 65536, MAX_CACHE = 64;
    private final HttpClient client;
    private final Loader loader;
    private final LongSupplier clock;
    private final Semaphore download = new Semaphore(1);
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>();
    private volatile boolean closed;
    @FunctionalInterface interface Loader { byte[] load(URI uri) throws IOException, InterruptedException; }
    private record Entry(byte[] face, long until) { }

    public SkinFaces() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        loader = uri -> {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                    .header("Accept", "image/png").header("User-Agent", "RemoteDatapack/0.1").GET().build();
            var response = client.send(request, info -> new LimitedBody(MAX_BYTES));
            if (response.statusCode() != 200) throw new IOException("Skin unavailable");
            return response.body();
        };
        clock = System::nanoTime;
    }
    /** Bounded Java 21 byte-array subscriber; cancel before forwarding an oversized chunk. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long received;
        private java.util.concurrent.Flow.Subscription subscription;
        private boolean done;
        LimitedBody(long limit) { this.limit = limit; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription = subscription; delegate.onSubscribe(subscription);
        }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            if (done) return;
            for (var buffer : buffers) {
                received += buffer.remaining();
                if (received > limit) {
                    done = true; subscription.cancel(); delegate.onError(new IOException("Skin too large")); return;
                }
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
        public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }

    SkinFaces(Loader loader, LongSupplier clock) { this.client = null; this.loader = loader; this.clock = clock; }

    /** Rebuild the URL, allowing only the official host and a texture hash, always over HTTPS. */
    public static String source(String url) {
        if (url == null || !url.matches("https?://textures\\.minecraft\\.net/texture/[a-f0-9]{32,64}")) return null;
        return "https://textures.minecraft.net/texture/" + url.substring(url.lastIndexOf('/') + 1);
    }
    public byte[] face(String url) {
        String key = source(url);
        if (closed || key == null) return null;
        synchronized (cache) {
            Entry existing = cache.get(key);
            if (existing != null && clock.getAsLong() < existing.until) return existing.face;
        }
        // Do not queue behind a slow CDN; fallback initials leave auth/file HTTP workers available.
        if (!download.tryAcquire()) return null;
        try {
            if (closed) return null;
            synchronized (cache) {
                Entry existing = cache.get(key);
                if (existing != null && clock.getAsLong() < existing.until) return existing.face;
            }
            byte[] face = null;
            try { face = render(loader.load(URI.create(key))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException | RuntimeException ignored) { /* Cosmetic failure; no source URL or identity logs. */ }
            synchronized (cache) {
                if (closed) return null;
                cache.put(key, new Entry(face, clock.getAsLong() + Duration.ofSeconds(face == null ? 60 : 3600).toNanos()));
                while (cache.size() > MAX_CACHE) cache.remove(cache.keySet().iterator().next());
            }
            return face;
        } finally { download.release(); }
    }
    static byte[] render(byte[] png) throws IOException {
        byte[] magic = {(byte)137,80,78,71,13,10,26,10};
        if (png.length > MAX_BYTES || png.length < magic.length) throw new IOException("Invalid skin size");
        for (int i = 0; i < magic.length; i++) if (png[i] != magic[i]) throw new IOException("PNG required");
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(input, true, true);
            int height = reader.getHeight(0);
            if (reader.getWidth(0) != 64 || (height != 32 && height != 64)) throw new IOException("Unsupported skin dimensions");
            BufferedImage skin = reader.read(0);
            BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            // Minecraft treats base face pixels as opaque; the outer hat layer retains alpha.
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) face.setRGB(x, y, skin.getRGB(x + 8, y + 8) | 0xff000000);
            boolean legacyOpaque = height == 32;
            if (legacyOpaque) for (int y = 0; y < 16; y++) for (int x = 32; x < 64; x++)
                if ((skin.getRGB(x, y) >>> 24) < 128) legacyOpaque = false;
            if (!legacyOpaque) {
                var graphics = face.createGraphics();
                try { graphics.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null); }
                finally { graphics.dispose(); }
            }
            var output = new ByteArrayOutputStream();
            try (var stream = new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
                ImageIO.write(face, "png", stream);
            }
            return output.toByteArray();
        } finally { reader.dispose(); }
    }
    @Override public void close() {
        closed = true;
        synchronized (cache) { cache.clear(); }
        if (client != null) client.shutdownNow();
    }
}
