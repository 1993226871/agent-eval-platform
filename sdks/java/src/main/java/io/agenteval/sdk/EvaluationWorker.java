package io.agenteval.sdk;
import io.agenteval.protocol.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

public final class EvaluationWorker implements AutoCloseable {
    private final WorkerTransport transport;
    private final FileSpool spool;
    private final Map<UUID,Function<TaskContext,AgentResult>> handlers=new ConcurrentHashMap<>();
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final CountDownLatch stopSignal=new CountDownLatch(1);
    private final Set<CancellationToken> active=ConcurrentHashMap.newKeySet();
    private final Set<Thread> heartbeats=ConcurrentHashMap.newKeySet();
    private final Set<Thread> uploaders=ConcurrentHashMap.newKeySet();
    private final List<Thread> pollers=new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService deadlines=Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon(true).name("agent-eval-deadline-",0).factory());
    public EvaluationWorker(WorkerTransport transport,FileSpool spool) { this.transport=transport; this.spool=spool; }
    public static EvaluationWorker fromEnvironment() { return fromEnvironment(System.getenv()); }
    public static EvaluationWorker fromEnvironment(Map<String,String> environment) {
        var url=environment.get("AGENT_EVAL_URL"); var key=environment.get("AGENT_EVAL_API_KEY");
        if(url==null || key==null) throw new IllegalArgumentException("AGENT_EVAL_URL and AGENT_EVAL_API_KEY are required");
        var transport=new HttpWorkerTransport(java.net.URI.create(url),key,environment.getOrDefault("AGENT_EVAL_AGENT_VERSION","v1"),environment.getOrDefault("AGENT_EVAL_INSTANCE_ID",UUID.randomUUID().toString()));
        try { return new EvaluationWorker(transport,new FileSpool(Path.of(environment.getOrDefault("AGENT_EVAL_SPOOL",".agent-eval/java-spool.sqlite")))); }
        catch(RuntimeException failure) { transport.close(); throw failure; }
    }
    public EvaluationWorker registerAgent(String id,Function<TaskContext,AgentResult> handler) {
        if(!pollers.isEmpty() || stopped.get() || handlers.putIfAbsent(UUID.fromString(id),Objects.requireNonNull(handler))!=null) throw new IllegalArgumentException("Register each Agent once before start"); return this;
    }
    public int activeHeartbeatCount() { return (int)heartbeats.stream().filter(Thread::isAlive).count(); }
    public void execute(UUID agent,LeaseGrant grant) {
        var handler=handlers.get(agent); if(handler==null || stopped.get()) return;
        if(!spool.markExecution(grant.trialId(),grant.attemptToken())) { flushPayloads(grant); flush(grant); return; }
        if(!Instant.now().isBefore(grant.task().deadline())) return;
        try { transport.started(grant); } catch(WorkerRejectedException | TransportException rejected) { return; }
        var cancel=new CancellationToken(); active.add(cancel); var done=new AtomicBoolean(); var ownershipLost=new AtomicBoolean();
        var leaseExpiry=new AtomicReference<>(grant.leaseExpiresAt());
        var deadline=deadlines.schedule(cancel::cancel,Math.max(0,Duration.between(Instant.now(),grant.task().deadline()).toMillis()),TimeUnit.MILLISECONDS);
        var heartbeat=Thread.ofPlatform().daemon(true).name("agent-eval-heartbeat").unstarted(() -> {
            while(!done.get()) {
                var expiry=leaseExpiry.get().isBefore(grant.task().deadline())?leaseExpiry.get():grant.task().deadline();
                long wait=Math.max(0,Math.min(transport.heartbeatInterval().toMillis(),Duration.between(Instant.now(),expiry).toMillis()));
                try { Thread.sleep(wait); } catch(InterruptedException stoppedThread) { return; }
                if(done.get()) return;
                if(stopped.get() || !Instant.now().isBefore(expiry)) { cancel.cancel(); if(!Instant.now().isBefore(leaseExpiry.get())) ownershipLost.set(true); return; }
                try {
                    var response=transport.heartbeat(grant); leaseExpiry.set(response.leaseExpiresAt()); if(response.cancelRequested()) cancel.cancel();
                } catch(WorkerRejectedException failure) { if(Set.of(401,403,404,409).contains(failure.status())) { ownershipLost.set(true); cancel.cancel(); return; } }
                catch(TransportException failure) { /* Last acknowledged lease remains authoritative. */ }
            }
        });
        heartbeats.add(heartbeat); heartbeat.start();
        var uploader=Thread.ofPlatform().daemon(true).name("agent-eval-uploader").unstarted(() -> {
            while(!done.get() && !Thread.currentThread().isInterrupted()) {
                flushPayloads(grant);
                flush(grant);
                try { Thread.sleep(1000); } catch(InterruptedException stoppedThread) { return; }
            }
        });
        uploaders.add(uploader); uploader.start();
        var secrets=new ArrayList<>(transport.secrets()); secrets.add(grant.attemptToken());
        var telemetry=new Telemetry(grant.trialId(),grant.traceId(),spool,secrets);
        var artifacts=new CopyOnWriteArrayList<UUID>();
        var context=new TaskContext(grant.trialId(),grant.task().instruction(),grant.task().input(),grant.task().deadline(),cancel,(path,name,media) -> { UUID id=transport.uploadArtifact(grant,path,name,media); artifacts.add(id); return id; });
        AgentResult result=null; var status=TrialExecutionStatus.SUCCEEDED;
        try(var activeScope=telemetry.activate(); var root=Telemetry.span("agent","agent.run",context.input())) {
            try { result=Objects.requireNonNull(handler.apply(context)); root.output(result.output()); }
            catch(RuntimeException failure) { root.failed(failure); status=TrialExecutionStatus.FAILED; }
        } finally {
            try {
                flushPayloads(grant); flush(grant); if(cancel.isCancelled()) status=TrialExecutionStatus.CANCELLED; if(!Instant.now().isBefore(context.deadline())) status=TrialExecutionStatus.TIMED_OUT;
                var stats=spool.stats(grant.trialId());
                if(result!=null) artifacts.addAll(result.artifactIds()); artifacts.addAll(spool.artifactIds(grant.trialId()));
                var completion=new CompletionRequest(status,result==null?JsonSupport.tree("null"):result.output(),stats.finalSequence(),stats.eventCount(),stats.droppedCount(),List.copyOf(new LinkedHashSet<>(artifacts)));
                if(!ownershipLost.get()) complete(grant,completion);
            } finally {
                done.set(true); heartbeat.interrupt(); uploader.interrupt(); try { heartbeat.join(1000); uploader.join(1000); } catch(InterruptedException stoppedThread) { Thread.currentThread().interrupt(); }
                deadline.cancel(false); active.remove(cancel); if(!heartbeat.isAlive()) heartbeats.remove(heartbeat);
                if(!uploader.isAlive()) uploaders.remove(uploader);
            }
        }
    }
    private void complete(LeaseGrant grant,CompletionRequest completion) {
        for(int attempt=0;attempt<3 && !Thread.currentThread().isInterrupted();attempt++) {
            try { transport.complete(grant,completion); return; }
            catch(WorkerRejectedException rejected) { if(rejected.status()<500) return; }
            catch(TransportException unavailable) {}
            try { Thread.sleep(100L<<attempt); } catch(InterruptedException stoppedThread) { Thread.currentThread().interrupt(); return; }
        }
    }
    private synchronized void flushPayloads(LeaseGrant grant) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) {
            var pending=spool.pendingPayloads(grant.trialId()); if(pending.isEmpty()) return; var payload=pending.getFirst();
            try {
                var id=transport.uploadBytes(grant,payload.content(),"payload.json","application/json",payload.id());
                spool.acknowledgePayload(grant.trialId(),payload.id(),id);
                new Telemetry(grant.trialId(),grant.traceId(),spool,List.of()).emit(EventType.ARTIFACT_CREATED,SpanKind.CUSTOM,"payload.attachment",UUID.randomUUID().toString().replace("-","").substring(0,16),null,Map.of("artifact_id",id.toString(),"payload_ref",payload.id()));
            } catch(WorkerRejectedException | TransportException | IllegalArgumentException failure) { return; }
        }
    }
    private void flush(LeaseGrant grant) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) {
            var events=spool.pending(grant.trialId()); if(events.isEmpty()) return;
            try { var ids=transport.uploadEvents(grant,events); var sent=new HashSet<>(events.stream().map(TraceEvent::eventId).toList()); if(ids.isEmpty() || !sent.containsAll(ids)) return; spool.acknowledge(grant.trialId(),ids); }
            catch(WorkerRejectedException | TransportException failure) { return; }
        }
    }
    public void start() {
        if(!pollers.isEmpty() || handlers.isEmpty() || stopped.get()) throw new IllegalStateException("Worker cannot start");
        for(var agent:handlers.keySet()) { var thread=Thread.ofPlatform().daemon(true).name("agent-eval-poller").unstarted(() -> poll(agent)); pollers.add(thread); thread.start(); }
    }
    public void startAndAwait() { start(); try { stopSignal.await(); } catch(InterruptedException stoppedThread) { Thread.currentThread().interrupt(); close(); } }
    private void poll(UUID agent) {
        UUID worker=null; long delay=250;
        while(!stopped.get()) {
            try { if(worker==null) worker=transport.register(agent); var grant=transport.claim(worker,agent); if(!stopped.get()) grant.ifPresent(value -> execute(agent,value)); delay=250; }
            catch(WorkerRejectedException failure) { if(Set.of(401,403,404).contains(failure.status())) return; waitBackoff(delay); delay=Math.min(delay*2,10000); }
            catch(TransportException | IllegalArgumentException failure) { waitBackoff(delay); delay=Math.min(delay*2,10000); }
        }
    }
    private void waitBackoff(long delay) { try { stopSignal.await(delay+ThreadLocalRandom.current().nextLong(delay/4+1),TimeUnit.MILLISECONDS); } catch(InterruptedException stoppedThread) { Thread.currentThread().interrupt(); stopped.set(true); } }
    @Override public void close() {
        stopped.set(true); stopSignal.countDown(); active.forEach(CancellationToken::cancel); pollers.forEach(Thread::interrupt); heartbeats.forEach(Thread::interrupt); uploaders.forEach(Thread::interrupt); deadlines.shutdownNow();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        for(var poller:pollers) try { poller.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime()))); } catch(InterruptedException stoppedThread) { Thread.currentThread().interrupt(); break; }
        if(active.isEmpty() && pollers.stream().noneMatch(Thread::isAlive) && heartbeats.stream().noneMatch(Thread::isAlive) && uploaders.stream().noneMatch(Thread::isAlive)) { transport.close(); spool.close(); }
    }
}
