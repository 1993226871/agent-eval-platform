package io.agenteval.sdk;
import java.time.Duration;
import java.util.concurrent.*;
public final class CancellationToken {
    private final CountDownLatch signal=new CountDownLatch(1);
    public boolean isCancelled() { return signal.getCount()==0; }
    public void cancel() { signal.countDown(); }
    public boolean await(Duration timeout) { try { return signal.await(timeout.toNanos(),TimeUnit.NANOSECONDS); } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); return true; } }
}
