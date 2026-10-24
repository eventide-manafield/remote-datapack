package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import studio.manafield.remotedatapack.config.WebConfig;
import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.datapack.DatapackService;
import studio.manafield.remotedatapack.file.FileService;
import studio.manafield.remotedatapack.apply.ApplyCoordinator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Web assets, liveness and optional authenticated session endpoints. */
public final class HttpServerService implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final WebConfig config;
    private final WebAssets assets;
    private final Set<String> allowedHosts;
    private final SessionHttpHandler auth;
    private final PacksHttpHandler packs;
    private final FilesHttpHandler files;
    private final FileService fileService;
    private final ApplyHttpHandler apply;
    private final ApplyCoordinator coordinator;
    private final TemplatesHttpHandler templates;

    private HttpServerService(WebConfig config, WebAssets assets, AuthConfig authConfig, AccessManager access, MainThreadGate gate, DatapackService datapacks, FileService fileService, ApplyCoordinator coordinator, studio.manafield.remotedatapack.datapack.PackFormat format) throws IOException {
        this.coordinator=coordinator;
        this.fileService = fileService;
        this.config = config;
        this.assets = assets;
        String publicHost = config.publicUrl().isEmpty() ? "localhost" : URI.create(config.publicUrl()).getHost();
        allowedHosts = Set.copyOf(java.util.List.of("localhost", "127.0.0.1", "[::1]", "[0:0:0:0:0:0:0:1]", config.bind().getHostAddress(), publicHost.toLowerCase(java.util.Locale.ROOT)));
        workers = new ThreadPoolExecutor(config.workers(), config.workers(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()), threads("http"), new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        deadlines = new ScheduledThreadPoolExecutor(1, threads("deadline"));
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        HttpServer created = null;
        SessionHttpHandler createdAuth = null;
        try {
            created = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), config.queueCapacity());
            String origin = authConfig == null ? "" : authConfig.origin(config, created.getAddress().getPort());
            auth = origin.isEmpty() ? null : new SessionHttpHandler(authConfig, origin, access, gate);
            createdAuth = auth;
            if(auth!=null && coordinator!=null)auth.applyStatus(coordinator::json);
            packs = auth == null || datapacks == null ? null : new PacksHttpHandler(auth, access, gate, datapacks);
            files = auth == null || fileService == null ? null : new FilesHttpHandler(auth, access, gate, fileService);
            apply = auth == null || coordinator == null ? null : new ApplyHttpHandler(auth,access,gate,coordinator);
            templates = auth == null || format == null ? null : new TemplatesHttpHandler(auth, access, gate, format);
            created.setExecutor(workers);
            created.createContext("/", this::handle);
            server = created;
            created.start();
        } catch (IOException | RuntimeException e) {
            if (createdAuth != null) createdAuth.close();
            if (created != null) created.stop(0);
            workers.shutdownNow();
            deadlines.shutdownNow();
            throw e;
        }
    }

    public static HttpServerService start(WebConfig config, WebAssets assets) throws IOException {
        HttpServerLimits.validate();
        return new HttpServerService(config, assets, null, null, null, null, null, null, null);
    }

    public static HttpServerService start(WebConfig config, WebAssets assets, AuthConfig authConfig, AccessManager access, MainThreadGate gate) throws IOException {
        HttpServerLimits.validate();
        return new HttpServerService(config, assets, authConfig, access, gate, null, null, null, null);
    }

    public static HttpServerService start(WebConfig config, WebAssets assets, AuthConfig authConfig,
                                          AccessManager access, MainThreadGate gate, DatapackService datapacks) throws IOException {
        HttpServerLimits.validate();
        return new HttpServerService(config, assets, authConfig, access, gate, datapacks, null, null, null);
    }

    public static HttpServerService start(WebConfig config, WebAssets assets, AuthConfig authConfig,
                                          AccessManager access, MainThreadGate gate, DatapackService datapacks, FileService files) throws IOException {
        HttpServerLimits.validate();
        return new HttpServerService(config, assets, authConfig, access, gate, datapacks, files, null, null);
    }
    public static HttpServerService start(WebConfig config, WebAssets assets, AuthConfig authConfig,
            AccessManager access, MainThreadGate gate, DatapackService datapacks, FileService files, ApplyCoordinator coordinator) throws IOException {
        HttpServerLimits.validate();return new HttpServerService(config,assets,authConfig,access,gate,datapacks,files,coordinator,null);
    }
    public static HttpServerService start(WebConfig config, WebAssets assets, AuthConfig authConfig,
            AccessManager access, MainThreadGate gate, DatapackService datapacks, FileService files,
            ApplyCoordinator coordinator, studio.manafield.remotedatapack.datapack.PackFormat format) throws IOException {
        HttpServerLimits.validate();
        return new HttpServerService(config, assets, authConfig, access, gate, datapacks, files, coordinator, format);
    }
    public String authOrigin() { return auth == null ? "" : auth.origin(); }

    private static ThreadFactory threads(String role) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "remote-datapack-" + role + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void handle(HttpExchange exchange) {
        if (closed.get()) { exchange.close(); return; }
        java.util.concurrent.ScheduledFuture<?> deadline;
        try {
            deadline = deadlines.schedule(exchange::close, config.responseTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            exchange.close();
            return;
        }
        try (exchange) {
            headers(exchange);
            if (!validHost(exchange)) { json(exchange, 400, "invalid_host"); return; }
            String path = exchange.getRequestURI().getRawPath();
            if (path.length() > 2048 || exchange.getRequestURI().toASCIIString().length() > 4096) {
                json(exchange, 414, "uri_too_long"); return;
            }
            if (templates != null && path.equals("/api/templates")) { templates.handle(exchange); return; }
            if (apply != null && (path.equals("/api/apply") || path.equals("/api/packs/apply"))) {apply.handle(exchange);return;}
            if (files != null && files.matches(path)) { files.handle(exchange); return; }
            if (packs != null && path.equals("/api/packs")) { packs.handle(exchange); return; }
            if (auth != null && auth.matches(path)) { auth.handle(exchange); return; }
            if (exchange.getRequestHeaders().containsKey("Transfer-Encoding") || hasBody(exchange)) {
                json(exchange, 413, "request_body_not_supported"); return;
            }
            // Unregistered management routes stay unavailable.
            if (path.equals("/api") || path.startsWith("/api/")) {
                json(exchange, 503, "management_unavailable"); return;
            }
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                json(exchange, 405, "method_not_allowed"); return;
            }
            if (path.equals("/health")) {
                send(exchange, 200, "application/json; charset=utf-8", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            WebAssets.Asset asset = assets.get(path);
            if (asset == null) { json(exchange, 404, "not_found"); return; }
            send(exchange, 200, asset.contentType(), asset.bytes());
        } catch (IOException | IllegalArgumentException ignored) {
            // Client disconnects/timeouts are not access logs; never log URI, tokens or cookies.
        } finally {
            deadline.cancel(false);
            exchange.close();
        }
    }

    private boolean validHost(HttpExchange exchange) {
        var hosts = exchange.getRequestHeaders().get("Host");
        if (hosts == null || hosts.size() != 1) return false;
        try {
            URI authority = URI.create("http://" + hosts.getFirst());
            return authority.getHost() != null && authority.getRawUserInfo() == null
                    && authority.getRawQuery() == null && authority.getRawFragment() == null
                    && authority.getRawPath().isEmpty()
                    && authority.getPort() != 0 && authority.getPort() <= 65535
                    && !hosts.getFirst().endsWith(":")
                    && (config.directHttp() ? directHost(authority)
                    : allowedHosts.contains(authority.getHost().toLowerCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) { return false; }
    }

    private boolean directHost(URI authority) {
        String host = authority.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = authority.getPort() == -1 ? 80 : authority.getPort();
        // Keep loopback health checks available without trusting arbitrary external Host headers.
        if (Set.of("localhost", "127.0.0.1", "[::1]", "[0:0:0:0:0:0:0:1]").contains(host)
                && port == server.getAddress().getPort()) return true;
        if (config.publicUrl().isEmpty()) return false;
        URI expected = URI.create(config.publicUrl());
        return host.equals(expected.getHost().toLowerCase(java.util.Locale.ROOT))
                && port == (expected.getPort() == -1 ? 80 : expected.getPort());
    }

    private static boolean hasBody(HttpExchange exchange) {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        return length != null && !length.equals("0");
    }

    private static void headers(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        // Close after each response; do not retain attacker-controlled request connections.
        headers.set("Connection", "close");
    }

    private static void json(HttpExchange exchange, int status, String code) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    public int port() { return server.getAddress().getPort(); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            if(coordinator!=null)coordinator.close();
            if (fileService != null) fileService.close();
            if (auth != null) auth.close();
            server.stop(0);
            deadlines.shutdownNow();
            workers.shutdownNow();
        }
    }
}
