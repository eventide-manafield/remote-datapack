package studio.manafield.remotedatapack.auth;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded next-tick bridge. Only HTTP threads wait, never the Paper tick thread. */
public final class MainThreadGate implements AutoCloseable {
    private final Executor main;
    private final long timeoutNanos;
    private final Semaphore capacity = new Semaphore(32);
    private final Set<Pending<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    public MainThreadGate(Executor main, Duration timeout) { this.main = main; timeoutNanos = timeout.toNanos(); }
    public <T> T call(Callable<T> action) {
        if (closed.get() || !capacity.tryAcquire()) throw new AuthFailure(503, "auth_busy");
        Pending<T> ticket = new Pending<>(action);
        pending.add(ticket);
        try {
            if (closed.get()) { ticket.cancel(); ticket.release(); }
            else {
                try { main.execute(ticket); }
                catch (RuntimeException rejected) { ticket.cancel(); ticket.release(); }
            }
            return ticket.result.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            ticket.cancel(); Thread.currentThread().interrupt(); throw new AuthFailure(503, "auth_unavailable");
        } catch (TimeoutException e) {
            ticket.cancel(); throw new AuthFailure(503, "main_thread_timeout");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AuthFailure failure) throw failure;
            throw new AuthFailure(503, "auth_unavailable");
        }
    }
    private final class Pending<T> implements Runnable {
        final Callable<T> action;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final long deadline = System.nanoTime() + timeoutNanos;
        final AtomicBoolean released = new AtomicBoolean();
        Pending(Callable<T> action) { this.action = action; }
        void cancel() { result.completeExceptionally(new AuthFailure(503, "auth_unavailable")); }
        void release() { if (released.compareAndSet(false, true)) { pending.remove(this); capacity.release(); } }
        @Override public void run() {
            try {
                if (closed.get() || result.isDone() || System.nanoTime() >= deadline) { cancel(); return; }
                result.complete(action.call());
            } catch (Exception e) { result.completeExceptionally(e); }
            finally { release(); }
        }
    }
    @Override public void close() {
        closed.set(true);
        for (Pending<?> ticket : pending) { ticket.cancel(); ticket.release(); }
    }
}
