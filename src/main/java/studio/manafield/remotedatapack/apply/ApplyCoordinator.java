package studio.manafield.remotedatapack.apply;

import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.http.Json;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Server-wide admission and monotonic cooldown. No I/O, Bukkit calls or periodic tasks under this monitor. */
public final class ApplyCoordinator implements AutoCloseable {
    private final long cooldownNanos;
    private final LongSupplier clock;
    private final Executor main;
    private final Runnable reload;
    private final AccessManager access;
    private final Consumer<String> audit;
    private final boolean supported;
    private final LinkedHashMap<String,String> changes=new LinkedHashMap<>();
    private boolean closed, fileBusy, applying, finished, recoveryRequired;
    private long ended, revision, attempts;
    private String lastResult="clean", error="";

    public ApplyCoordinator(int seconds,LongSupplier clock,Executor main,Runnable reload,AccessManager access,Consumer<String> audit,boolean supported) {
        if(seconds<1 || seconds>3600) throw new IllegalArgumentException("apply.cooldown-seconds must be 1..3600");
        cooldownNanos=seconds*1_000_000_000L; this.clock=clock;this.main=main;this.reload=reload;this.access=access;this.audit=audit;this.supported=supported;
    }
    public static int cooldown(Map<String,Object> values) {
        Object value=values.getOrDefault("apply.cooldown-seconds",30);
        if(!(value instanceof Integer n) || n<1 || n>3600) throw new IllegalArgumentException("apply.cooldown-seconds must be an integer in 1..3600");
        return n;
    }
    public synchronized void beginFile() {
        live(); if(applying || fileBusy) throw new AuthFailure(409,"operation_busy"); fileBusy=true;
    }
    public synchronized void endFile() {fileBusy=false;}
    public synchronized void changed(String name,String action) {
        changes.put(name,action);revision++;
        // Bound metadata while retaining an honest aggregate marker if too many files changed.
        if(changes.size()>512) {changes.remove(changes.keySet().iterator().next());changes.put("기타 변경사항","multiple");}
    }
    public synchronized boolean pending() {return !changes.isEmpty();}
    public synchronized void recoveryRequired() {recoveryRequired=true;}
    public synchronized long remainingMillis() {
        if(!finished)return 0;
        return Math.max(0,(cooldownNanos-(clock.getAsLong()-ended)+999999)/1000000);
    }
    /** Main-thread request validation is short. The reload itself is scheduled separately, never through a timed HTTP gate. */
    public String request(AccessManager.Lease lease,long expectedRevision) {
        synchronized(this) {
            live();access.validate(lease);
            if(!supported)throw new AuthFailure(503,"apply_unsupported_build");
            if(applying || fileBusy)throw new AuthFailure(409,"operation_busy");
            if(remainingMillis()>0)throw new AuthFailure(429,"apply_cooldown");
            if(changes.isEmpty())throw new AuthFailure(409,"nothing_to_apply");
            if(revision!=expectedRevision)throw new AuthFailure(409,"changes_changed");
            applying=true;error="";
        }
        try {main.execute(()->execute(lease));}
        catch(RuntimeException failure) {finish(false,"apply_schedule_failed",false,lease);throw new AuthFailure(503,"apply_schedule_failed");}
        return json();
    }
    private void execute(AccessManager.Lease lease) {
        boolean started=false,success=false;String failure="";
        try {
            synchronized(this) {live();}
            access.validate(lease);access.reserveCommit(lease);
            synchronized(this) {if(closed)throw new AuthFailure(503,"apply_closed");attempts++;started=true;}
            reload.run(); // Main thread, outside monitor; never cancelled once this begins.
            success=true;
        } catch(RuntimeException e) { failure=e instanceof AuthFailure f?f.getMessage():"reload_failed"; }
        finally {finish(success,failure,started,lease);}
    }
    private void finish(boolean success,String failure,boolean started,AccessManager.Lease lease) {
        synchronized(this) {
            if(started) {ended=clock.getAsLong();finished=true;}
            applying=false;lastResult=success?"success":"failed";error=failure;
            if(success && !closed) {changes.clear();revision++;}
        }
        audit.accept("player="+lease.playerId()+" action=apply result="+(success?"success":"failed")+" code="+failure);
    }
    private void live() {
        if(closed)throw new AuthFailure(503,"apply_closed");
        if(recoveryRequired)throw new AuthFailure(503,"recovery_required");
    }
    public synchronized String json() {
        String state=closed?"unavailable":applying?"applying":lastResult.equals("failed")?"failed":!changes.isEmpty()?"pending":lastResult;
        var rows=new ArrayList<String>();changes.forEach((name,action)->rows.add("{\"name\":"+Json.quote(name)+",\"action\":"+Json.quote(action)+"}"));
        return "{\"state\":"+Json.quote(state)+",\"lastResult\":"+Json.quote(lastResult)+",\"error\":"+Json.quote(error)
                +",\"pending\":"+!changes.isEmpty()+",\"applying\":"+applying+",\"fileBusy\":"+fileBusy+",\"revision\":"+revision
                +",\"cooldownRemainingMs\":"+remainingMillis()+",\"attempts\":"+attempts+",\"supported\":"+supported
                +",\"activationSupported\":false,\"recoveryRequired\":"+recoveryRequired+",\"changes\":["+String.join(",",rows)+"]}";
    }
    @Override public synchronized void close() {closed=true;}
}
