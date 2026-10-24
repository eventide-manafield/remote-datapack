package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.HttpExchange;
import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.apply.ApplyCoordinator;
import java.io.IOException;

final class ApplyHttpHandler {
    private final SessionHttpHandler auth;private final AccessManager access;private final MainThreadGate gate;private final ApplyCoordinator apply;
    ApplyHttpHandler(SessionHttpHandler auth,AccessManager access,MainThreadGate gate,ApplyCoordinator apply) {this.auth=auth;this.access=access;this.gate=gate;this.apply=apply;}
    void handle(HttpExchange exchange) throws IOException {
        try {
            boolean read=exchange.getRequestURI().getRawPath().equals("/api/apply");
            if(read) {
                String session=auth.authorizeRead(exchange);gate.call(()->access.session(session));
                SessionHttpHandler.json(exchange,200,apply.json());
            } else {
                String session=auth.authorizeWrite(exchange,"POST");
                if(SessionHttpHandler.contentLength(exchange)!=0)throw new AuthFailure(413,"request_body_not_supported");
                String revision=SessionHttpHandler.single(exchange,"X-Apply-Revision");
                if(revision==null || !revision.matches("[0-9]{1,18}"))throw new AuthFailure(409,"confirmation_required");
                String csrf=SessionHttpHandler.single(exchange,"X-CSRF-Token");
                String result=gate.call(()->apply.request(access.lease(session,csrf),Long.parseLong(revision)));
                SessionHttpHandler.json(exchange,202,result);
            }
        } catch(AuthFailure failure) {
            if(failure.status()==429)exchange.getResponseHeaders().set("Retry-After",Long.toString((apply.remainingMillis()+999)/1000));
            auth.failure(exchange,failure);
        }
    }
}
