package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.HttpExchange;
import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.datapack.DatapackService;
import java.io.IOException;

final class PacksHttpHandler {
    private final SessionHttpHandler auth;
    private final AccessManager access;
    private final MainThreadGate gate;
    private final DatapackService packs;
    PacksHttpHandler(SessionHttpHandler auth, AccessManager access, MainThreadGate gate, DatapackService packs) {
        this.auth = auth; this.access = access; this.gate = gate; this.packs = packs;
    }
    void handle(HttpExchange exchange) throws IOException {
        try {
            String session = auth.authorizeRead(exchange);
            // Deny anonymous requests before admission to the listing worker and before any pack access.
            gate.call(() -> access.session(session));
            String response = packs.list(() -> gate.call(() -> {
                access.session(session); return packs.snapshot();
            }), () -> gate.call(() -> access.session(session)));
            SessionHttpHandler.json(exchange, 200, response);
        } catch (AuthFailure failure) { auth.failure(exchange, failure); }
        catch (RuntimeException failure) { auth.failure(exchange, new AuthFailure(503, "packs_unavailable")); }
    }
}
