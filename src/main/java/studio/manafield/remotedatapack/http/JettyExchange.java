package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps application handlers independent of the embedded HTTP implementation. */
final class JettyExchange extends HttpExchange {
    private final Request request;
    private final Response response;
    private final URI uri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private InputStream input;
    private OutputStream output;
    private int status = -1;

    JettyExchange(Request request, Response response) {
        this.request = request;
        this.response = response;
        this.uri = URI.create(request.getHttpURI().asString());
        for (var field : request.getHeaders()) requestHeaders.add(field.getName(), field.getValue());
    }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return request.getMethod(); }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public InputStream getRequestBody() {
        if (input == null) input = Content.Source.asInputStream(request);
        return input;
    }
    @Override public OutputStream getResponseBody() {
        if (output == null) output = Content.Sink.asOutputStream(response);
        return output;
    }
    @Override public void sendResponseHeaders(int code, long length) throws IOException {
        if (closed.get() || status != -1) throw new IOException("Exchange is closed or already committed");
        status = code;
        response.setStatus(code);
        responseHeaders.forEach((name, values) -> values.forEach(value -> response.getHeaders().add(name, value)));
        if (length > 0) response.getHeaders().put("Content-Length", Long.toString(length));
        else if (length < 0 && !getRequestMethod().equals("HEAD")) response.getHeaders().put("Content-Length", "0");
    }
    @Override public int getResponseCode() { return status; }
    @Override public InetSocketAddress getRemoteAddress() { return (InetSocketAddress) request.getConnectionMetaData().getRemoteSocketAddress(); }
    @Override public InetSocketAddress getLocalAddress() { return (InetSocketAddress) request.getConnectionMetaData().getLocalSocketAddress(); }
    @Override public String getProtocol() { return request.getConnectionMetaData().getProtocol(); }
    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    @Override public void setStreams(InputStream in, OutputStream out) {
        if (in != null) input = in;
        if (out != null) output = out;
    }
    @Override public HttpPrincipal getPrincipal() { return null; }

    /** Normal handler completion flushes the response; deadline expiry must abort immediately. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (status < 0) abort();
            else getResponseBody().close();
        } catch (IOException | RuntimeException ignored) { abort(); }
    }
    void abort() { request.getConnectionMetaData().getConnection().getEndPoint().close(); }
}
