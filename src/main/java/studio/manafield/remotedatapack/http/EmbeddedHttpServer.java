package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.HttpHandler;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import studio.manafield.remotedatapack.config.WebConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Per-listener limits, independent of JVM properties and other plugins' HTTP servers. */
final class EmbeddedHttpServer implements AutoCloseable {
    private static final int MAX_CONNECTIONS = 64;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADERS = 64;
    private static final int HEADER_TIMEOUT_SECONDS = 10;
    private final Server server;
    private final ServerConnector connector;
    private final ConcurrentHashMap<Connection, ScheduledFuture<?>> headerDeadlines = new ConcurrentHashMap<>();
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
    private final Set<Connection> admitted = ConcurrentHashMap.newKeySet();

    EmbeddedHttpServer(WebConfig config, ThreadPoolExecutor workers, ScheduledThreadPoolExecutor deadlines, HttpHandler handler) throws IOException {
        QueuedThreadPool io = new QueuedThreadPool(8, 2, 30_000);
        io.setName("remote-datapack-transport");
        io.setDaemon(true);
        io.setReservedThreads(0);
        server = new Server(io);
        server.setStopTimeout(1000);
        HttpConfiguration http = new HttpConfiguration();
        http.setRequestHeaderSize(MAX_HEADER_BYTES);
        http.setSendServerVersion(false);
        http.setSendXPoweredBy(false);
        http.setPersistentConnectionsEnabled(false);
        http.setHttpCompliance(HttpCompliance.RFC9110);
        http.setUriCompliance(UriCompliance.RFC3986);
        connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory(http));
        connector.setHost(config.bind().getHostAddress());
        connector.setPort(config.port());
        connector.setAcceptQueueSize(config.queueCapacity());
        connector.setIdleTimeout(TimeUnit.SECONDS.toMillis(Math.max(HEADER_TIMEOUT_SECONDS, config.responseTimeoutSeconds())));
        connector.addBean(new Connection.Listener() {
            @Override public void onOpened(Connection connection) {
                // Enforce the exact cap even while the acceptor is pausing at its limit.
                if (!connectionSlots.tryAcquire()) { connection.getEndPoint().close(); return; }
                admitted.add(connection);
                try {
                    ScheduledFuture<?> timer = deadlines.schedule(() -> connection.getEndPoint().close(), HEADER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    headerDeadlines.put(connection, timer);
                    if (!connection.getEndPoint().isOpen()) onClosed(connection);
                } catch (RejectedExecutionException stopped) { connection.getEndPoint().close(); }
            }
            @Override public void onClosed(Connection connection) {
                cancelHeaderDeadline(connection);
                if (admitted.remove(connection)) connectionSlots.release();
            }
        });
        server.addConnector(connector);
        server.addBean(new NetworkConnectionLimit(MAX_CONNECTIONS, connector));
        // Neither parse errors nor handler failures may echo URLs, credentials or stack traces.
        server.setErrorHandler(new Handler.Abstract.NonBlocking() {
            @Override public boolean handle(Request request, Response response, Callback callback) {
                error(response, callback, response.getStatus() >= 400 ? response.getStatus() : 500);
                return true;
            }
        });
        server.setHandler(new Handler.Abstract.NonBlocking() {
            @Override public boolean handle(Request request, Response response, Callback callback) {
                if (request.getHeaders().size() > MAX_HEADERS) {
                    error(response, callback, 431);
                    return true;
                }
                try {
                    workers.execute(() -> {
                        Connection connection = request.getConnectionMetaData().getConnection();
                        if (!admitted.contains(connection) || !connection.getEndPoint().isOpen()) {
                            callback.failed(new IOException("Connection closed before request processing"));
                            return;
                        }
                        cancelHeaderDeadline(connection);
                        try {
                            JettyExchange exchange = new JettyExchange(request, response);
                            handler.handle(exchange);
                            exchange.close();
                            callback.succeeded();
                        } catch (Exception failure) {
                            request.getConnectionMetaData().getConnection().getEndPoint().close();
                            callback.failed(new IOException("HTTP request failed"));
                        }
                    });
                } catch (RejectedExecutionException full) { error(response, callback, 503); }
                return true;
            }
        });
        try { connector.open(); }
        catch (IOException | RuntimeException failure) { close(); throw failure; }
    }

    private void cancelHeaderDeadline(Connection connection) {
        ScheduledFuture<?> timer = headerDeadlines.remove(connection);
        if (timer != null) timer.cancel(false);
    }
    private static void error(Response response, Callback callback, int status) {
        response.setStatus(status);
        response.getHeaders().put("Content-Type", "application/json; charset=utf-8");
        response.getHeaders().put("Cache-Control", "no-store");
        response.getHeaders().put("Referrer-Policy", "no-referrer");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.getHeaders().put("Connection", "close");
        response.write(true, ByteBuffer.wrap("{\"error\":\"request_rejected\"}".getBytes(StandardCharsets.UTF_8)), callback);
    }
    int port() { return connector.getLocalPort(); }
    void start() throws IOException {
        try { server.start(); }
        catch (Exception failure) { close(); throw new IOException("Embedded HTTP server could not start", failure); }
    }
    @Override public void close() {
        connector.close();
        admitted.forEach(connection -> connection.getEndPoint().close());
        headerDeadlines.forEach((connection, timer) -> { timer.cancel(false); connection.getEndPoint().close(); });
        headerDeadlines.clear();
        try { server.stop(); }
        catch (Exception ignored) { /* No request content is logged during shutdown. */ }
    }
}
