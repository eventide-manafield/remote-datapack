package studio.manafield.remotedatapack.http;

import com.sun.net.httpserver.HttpExchange;
import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.file.FileService;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class FilesHttpHandler {
    private final SessionHttpHandler auth; private final AccessManager access; private final MainThreadGate gate; private final FileService files;
    FilesHttpHandler(SessionHttpHandler auth,AccessManager access,MainThreadGate gate,FileService files) { this.auth=auth; this.access=access; this.gate=gate; this.files=files; }
    boolean matches(String path) {
        return path.equals("/api/packs/upload") || path.matches("/api/packs/[a-f0-9]{64}(?:/(?:download|replace))?") || path.matches("/api/jobs/[a-f0-9-]{36}");
    }
    void handle(HttpExchange exchange) throws IOException {
        AtomicBoolean started=new AtomicBoolean();
        try {
            String path=exchange.getRequestURI().getRawPath();
            if(path.startsWith("/api/jobs/")) {
                String session=auth.authorizeRead(exchange); gate.call(()->access.session(session));
                SessionHttpHandler.json(exchange,200,files.job(path.substring(10),session)); return;
            }
            if(path.endsWith("/download")) {
                String session=auth.authorizeRead(exchange); var lease=gate.call(()->access.readLease(session));
                files.download(path.split("/")[3],lease,new FileService.Download() {
                    public void start(String name,long length) throws IOException {
                        exchange.getResponseHeaders().set("Content-Type","application/zip");
                        exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\"datapack.zip\"; filename*=UTF-8''"+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20"));
                        started.set(true); exchange.sendResponseHeaders(200,length);
                    }
                    public void write(byte[] bytes,int length) throws IOException { exchange.getResponseBody().write(bytes,0,length); }
                }); return;
            }
            boolean upload=path.equals("/api/packs/upload"), replace=path.endsWith("/replace");
            String session=auth.authorizeWrite(exchange,upload||replace?"POST":"DELETE");
            var lease=gate.call(()->access.lease(session,SessionHttpHandler.single(exchange,"X-CSRF-Token")));
            String confirm=null;
            if(!upload) {
                String header=SessionHttpHandler.single(exchange,"X-Pack-Confirm");
                if(header==null || header.length()>2048) throw new AuthFailure(409,"confirmation_required");
                try { confirm=URLDecoder.decode(header,StandardCharsets.UTF_8); }
                catch(IllegalArgumentException e) { throw new AuthFailure(400,"invalid_confirmation"); }
            }
            int length=SessionHttpHandler.contentLength(exchange);
            String response;
            if(upload || replace) {
                if(!"application/zip".equalsIgnoreCase(SessionHttpHandler.single(exchange,"Content-Type"))) throw new AuthFailure(415,"zip_required");
                if(length==0) throw new AuthFailure(411,"content_length_required");
                if(length>files.maxBytes()) throw new AuthFailure(413,"file_too_large");
                response=files.upload(exchange.getRequestBody(),length,upload?null:path.split("/")[3],confirm,lease);
            } else {
                if(length!=0) throw new AuthFailure(413,"request_body_not_supported");
                response=files.delete(path.split("/")[3],confirm,lease);
            }
            SessionHttpHandler.json(exchange,202,response);
        } catch(AuthFailure failure) { if(!started.get()) auth.failure(exchange,failure); }
        catch(RuntimeException failure) { if(!started.get()) auth.failure(exchange,new AuthFailure(503,"files_unavailable")); }
    }
}
