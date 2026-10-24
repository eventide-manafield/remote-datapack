package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.HttpExchange;
import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.datapack.PackFormat;
import studio.manafield.remotedatapack.file.TemplateArchive;
import java.io.IOException;

final class TemplatesHttpHandler {
    private final SessionHttpHandler auth;
    private final AccessManager access;
    private final MainThreadGate gate;
    private final PackFormat format;
    private final TemplateArchive templates;
    TemplatesHttpHandler(SessionHttpHandler auth, AccessManager access, MainThreadGate gate, PackFormat format) {
        this.auth = auth; this.access = access; this.gate = gate; this.format = format;
        templates = new TemplateArchive(format);
    }
    void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestMethod().equals("GET")) {
                String session = auth.authorizeRead(exchange);
                gate.call(() -> access.session(session));
                SessionHttpHandler.json(exchange, 200, format.json()); return;
            }
            String session = auth.authorizeWrite(exchange, "POST");
            var lease = gate.call(() -> access.lease(session, SessionHttpHandler.single(exchange, "X-CSRF-Token")));
            String type = SessionHttpHandler.single(exchange, "Content-Type");
            if (type == null || !type.matches("(?i)application/json(?:;\\s*charset=utf-8)?")) throw new AuthFailure(415, "json_required");
            int length = SessionHttpHandler.contentLength(exchange);
            if (length < 1) throw new AuthFailure(411, "content_length_required");
            if (length > 8192) throw new AuthFailure(413, "body_too_large");
            byte[] body = exchange.getRequestBody().readNBytes(length);
            if (body.length != length) throw new AuthFailure(400, "invalid_body");
            var generated = templates.generate(body);
            gate.call(() -> { access.validate(lease); return true; });
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + generated.filename() + "\"");
            exchange.sendResponseHeaders(200, generated.bytes().length);
            exchange.getResponseBody().write(generated.bytes());
        } catch (AuthFailure failure) { auth.failure(exchange, failure); }
    }
}
