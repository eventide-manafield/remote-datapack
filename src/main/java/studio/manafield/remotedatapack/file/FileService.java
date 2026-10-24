package studio.manafield.remotedatapack.file;

import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.apply.ApplyCoordinator;
import studio.manafield.remotedatapack.datapack.*;
import studio.manafield.remotedatapack.http.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** One disk worker, no queued writes. HTTP workers never run ZIP/backup/rename operations. */
public final class FileService implements AutoCloseable {
    private final ZipValidator validator;
    private final Path root; private final FileConfig config; private final AccessManager access; private final MainThreadGate gate;
    private final DatapackService packs; private final Refresh refresh; private final Consumer<String> audit;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{
        Thread thread=new Thread(task,"remote-datapack-files"); thread.setDaemon(true); return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Job> jobs=new LinkedHashMap<>(); private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<Job> active=new AtomicReference<>();
    private boolean cleaned; private volatile boolean recoveryRequired;
    public FileService(Path root,FileConfig config,AccessManager access,MainThreadGate gate,DatapackService packs,Refresh refresh,Consumer<String> audit,PackFormat format) {
        this.validator = new ZipValidator(format);
        this.root=root; this.config=config; this.access=access; this.gate=gate; this.packs=packs; this.refresh=refresh; this.audit=audit;
        io.allowCoreThreadTimeOut(true); if(root!=null) packs.enableFiles();
    }
    private ApplyCoordinator apply;
    public void coordinate(ApplyCoordinator apply) {this.apply=apply;}
    public long maxBytes() { return config.maxBytes(); }
    private static final class Job {
        final String id=UUID.randomUUID().toString(), action, target, confirmation;
        final AccessManager.Lease lease; final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        volatile String state="receiving", code="", filename="";
        final CompletableFuture<String> received=new CompletableFuture<>();
        Job(String action,String target,String confirmation,AccessManager.Lease lease) { this.action=action; this.target=target; this.confirmation=confirmation; this.lease=lease; }
        String json() { return "{\"id\":"+Json.quote(id)+",\"action\":"+Json.quote(action)+",\"state\":"+Json.quote(state)+",\"code\":"+Json.quote(code)+",\"name\":"+Json.quote(filename)+"}"; }
    }
    public String upload(InputStream input,long length,String target,String confirmation,AccessManager.Lease lease) {
        Job job=new Job(target==null?"upload":"replace",target,confirmation,lease); submit(job,()->mutate(job,input,length));
        try { return job.received.get(8,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); access.cancel(lease); throw new AuthFailure(503,"upload_interrupted"); }
        catch(TimeoutException e) { access.cancel(lease); throw new AuthFailure(408,"upload_timeout"); }
        catch(ExecutionException e) { if(e.getCause() instanceof AuthFailure f) throw f; throw new AuthFailure(503,"files_unavailable"); }
    }
    public String delete(String target,String confirmation,AccessManager.Lease lease) {
        Job job=new Job("delete",target,confirmation,lease); job.state="queued"; submit(job,()->mutate(job,null,0)); return job.json();
    }
    private void submit(Job job,Runnable action) {
        if(recoveryRequired) throw new AuthFailure(503,"recovery_required");
        if(closed.get() || root==null) throw new AuthFailure(503,"files_unavailable");
        if(apply!=null)apply.beginFile();
        if(!active.compareAndSet(null,job)) {if(apply!=null)apply.endFile();throw new AuthFailure(409,"operation_busy");}
        synchronized(jobs) { while(jobs.size()>=128) jobs.remove(jobs.keySet().iterator().next()); jobs.put(job.id,job); }
        try { io.execute(()->{ try { action.run(); } finally { active.compareAndSet(job,null); if(apply!=null)apply.endFile(); } }); }
        catch(RejectedExecutionException e) { if(apply!=null)apply.endFile(); active.compareAndSet(job,null); synchronized(jobs) { jobs.remove(job.id); } throw new AuthFailure(503,"files_unavailable"); }
    }
    public String job(String id,String session) {
        Job job; synchronized(jobs) { job=jobs.get(id); }
        if(job==null || !gate.call(()->access.owns(session,job.lease))) throw new AuthFailure(404,"job_not_found"); return job.json();
    }
    private void check(Job job) {
        if(closed.get() || Thread.currentThread().isInterrupted() || System.nanoTime()>=job.deadline) throw new AuthFailure(408,"operation_timeout");
        access.check(job.lease);
    }
    private List<PackSnapshot> snapshot(Job job) { return gate.call(()->{access.validate(job.lease); return packs.snapshot();}); }
    private DatapackService.Target target(Job job) {
        var target=packs.resolve(job.target,snapshot(job));
        if(!target.name().equals(job.confirmation)) throw new AuthFailure(409,"confirmation_mismatch");
        if(job.action.equals("delete") && target.enabled()) throw new AuthFailure(409,"active_pack_delete_disabled"); return target;
    }
    private void mutate(Job job,InputStream input,long length) {
        String temp=".rd-tmp-"+UUID.randomUUID()+".tmp", backup=null, originalHash=null, writtenHash=null;
        boolean changed=false; String filename=null;
        try(var dir=new PackDirectory(root)) {
            try {
                check(job); if(!cleaned) {dir.cleanAbandonedTemps();cleaned=true;} DatapackService.Target target=job.target==null?null:target(job);
                filename=target==null?"rd-"+UUID.randomUUID()+".zip":target.name(); job.filename=filename;
                if(target==null && dir.exists(filename)) throw new AuthFailure(409,"file_exists");
                if(input!=null) {
                    if(length<1 || length>config.maxBytes()) throw new AuthFailure(413,"file_too_large");
                    try(var output=dir.create(temp)) {
                        byte[] bytes=new byte[8192]; long remaining=length;
                        while(remaining>0) { check(job); int n=input.read(bytes,0,(int)Math.min(bytes.length,remaining)); if(n<0) throw new AuthFailure(400,"incomplete_upload");
                            ByteBuffer buffer=ByteBuffer.wrap(bytes,0,n); while(buffer.hasRemaining()) output.write(buffer); remaining-=n; }
                        PackDirectory.force(output);
                    }
                    job.state="validating"; job.received.complete(job.json());
                    try(var channel=dir.read(temp)) { validator.validate(channel,()->check(job)); writtenHash=PackDirectory.digest(channel,config.maxBytes(),()->check(job)); }
                }
                check(job); job.state="backing_up";
                if(target!=null) {
                    originalHash=dir.digest(filename,config.maxBytes(),()->check(job));
                    try(var channel=dir.read(filename)) { validator.validate(channel,()->check(job)); }
                    String prefix=".rd-backup-"+nameHash(filename)+"-";
                    var backups=dir.backups(); long total=backups.stream().mapToLong(PackDirectory.Backup::bytes).sum();
                    if(total+dir.attributes(filename).size()>config.backupBytes()) throw new AuthFailure(409,"backup_quota_exceeded");
                    backup=prefix+UUID.randomUUID()+".bak"; dir.copy(filename,backup,config.maxBytes(),()->check(job));
                    if(!originalHash.equals(dir.digest(backup,config.maxBytes(),()->check(job)))) throw new AuthFailure(409,"filesystem_changed");
                    prune(dir,filename,backup);
                }
                check(job); job.state="committing";
                // All potentially slow verification precedes the last main-thread authorization.
                dir.verifyRoot();
                if(target!=null && !originalHash.equals(dir.digest(filename,config.maxBytes(),()->check(job)))) throw new AuthFailure(409,"filesystem_changed");
                if(target==null && dir.exists(filename)) throw new AuthFailure(409,"file_exists");
                if(target!=null) target(job);
                gate.call(()->{access.validate(job.lease);return true;});
                check(job); access.reserveCommit(job.lease); // shared memory transition with quit/revoke, never I/O under main-thread lock
                if(job.action.equals("delete")) dir.remove(filename); else dir.move(temp,filename);
                changed=true; job.state="refreshing";
                String committedName=filename;
                try { gate.call(()->{refresh.update(committedName,job.action.equals("delete"));return true;}); }
                catch(RuntimeException failure) {
                    // Compensating restoration is allowed even after logout. It never applies a new user change.
                    if(backup==null) dir.remove(filename);
                    else { dir.copy(backup,temp,config.maxBytes(),()->{}); dir.move(temp,filename); }
                    changed=false;
                    try { gate.call(()->{refresh.update(committedName,job.action.equals("upload"));return true;}); } catch(RuntimeException ignored) { }
                    throw new AuthFailure(503,"refresh_failed_rolled_back");
                }
                packs.changed(); if(apply!=null)apply.changed(filename,job.action); job.state="pending_apply";
            } catch(Exception failure) {
                if(changed) {recoveryRequired=true;if(apply!=null)apply.recoveryRequired();}
                job.state=changed?"recovery_required":"failed";
                job.code=changed?"restore_failed":failure instanceof AuthFailure f?f.getMessage():"file_operation_failed";
                job.received.completeExceptionally(failure instanceof AuthFailure?failure:new AuthFailure(503,"file_operation_failed"));
            } finally { dir.removeQuietly(temp); }
        } catch(Exception failure) {
            job.state="failed"; job.code=failure instanceof AuthFailure f?f.getMessage():"files_unavailable";
            job.received.completeExceptionally(new AuthFailure(503,job.code));
        } finally {
            audit.accept("player="+job.lease.playerId()+" action="+job.action+" job="+job.id+" state="+job.state+" code="+job.code);
        }
    }
    private void prune(PackDirectory dir,String filename,String newest) throws IOException {
        var same=dir.backups().stream().filter(b->b.name().startsWith(".rd-backup-"+nameHash(filename)+"-")&&!b.name().equals(newest)).toList();
        for(int i=0;i<=same.size()-config.backupsPerPack();i++) dir.remove(same.get(i).name());
    }
    /** Download stays on the disk worker, validates before headers, and checks cancellation between chunks. */
    public void download(String id,AccessManager.Lease lease,Download output) {
        Job job=new Job("download",id,"",lease); CompletableFuture<Void> done=new CompletableFuture<>();
        submit(job,()->{
            try(var dir=new PackDirectory(root)) {
                check(job); var target=packs.resolve(id,snapshot(job));
                try(var channel=dir.read(target.name())) {
                    if(channel.size()>config.maxBytes()) throw new AuthFailure(413,"file_too_large");
                    validator.validate(channel,()->check(job)); gate.call(()->{access.validate(lease);return true;});
                    channel.position(0); output.start(target.name(),channel.size());
                    ByteBuffer buffer=ByteBuffer.allocate(8192);
                    while(channel.read(buffer)!=-1) { check(job); output.write(buffer.array(),buffer.position()); buffer.clear(); }
                    job.state="downloaded"; done.complete(null);
                }
            } catch(Exception e) { job.state="failed"; job.code=e instanceof AuthFailure f?f.getMessage():"download_failed"; done.completeExceptionally(e); }
            finally { audit.accept("player="+lease.playerId()+" action=download job="+job.id+" state="+job.state+" code="+job.code); }
        });
        try { done.get(8,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); access.cancel(lease); throw new AuthFailure(503,"download_interrupted"); }
        catch(TimeoutException e) { access.cancel(lease); throw new AuthFailure(408,"download_timeout"); }
        catch(ExecutionException e) { if(e.getCause() instanceof AuthFailure f) throw f; throw new AuthFailure(503,"download_failed"); }
    }
    @FunctionalInterface public interface Refresh { void update(String name,boolean deleted); }
    public interface Download { void start(String name,long length) throws IOException; void write(byte[] bytes,int length) throws IOException; }
    private static String nameHash(String name) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public void close() {
        closed.set(true); Job job=active.get(); if(job!=null) access.cancel(job.lease); io.shutdownNow();
    }
}
