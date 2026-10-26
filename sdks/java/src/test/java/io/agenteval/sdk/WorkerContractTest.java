package io.agenteval.sdk;
import io.agenteval.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class WorkerContractTest {
    @TempDir Path directory;
    final ProtocolCodec codec=new ProtocolCodec();
    static class FakeTransport implements WorkerTransport {
        final AtomicInteger started=new AtomicInteger();
        final List<CompletionRequest> completions=new CopyOnWriteArrayList<>();
        final List<TraceEvent> events=new CopyOnWriteArrayList<>();
        volatile boolean cancel,reject;
        public UUID register(UUID agent) { return UUID.randomUUID(); }
        public Optional<LeaseGrant> claim(UUID worker,UUID agent) { return Optional.empty(); }
        public void started(LeaseGrant grant) { started.incrementAndGet(); if(reject) throw new WorkerRejectedException(409); }
        public Heartbeat heartbeat(LeaseGrant grant) { return new Heartbeat(cancel,grant.leaseExpiresAt()); }
        public List<UUID> uploadEvents(LeaseGrant grant,List<TraceEvent> batch) { events.addAll(batch); return batch.stream().map(TraceEvent::eventId).toList(); }
        public void complete(LeaseGrant grant,CompletionRequest completion) { completions.add(completion); }
        public Duration heartbeatInterval() { return Duration.ofMillis(10); }
    }
    static LeaseGrant grant() {
        UUID trial=UUID.randomUUID();
        return new LeaseGrant(trial,UUID.randomUUID().toString().replace("-",""),"sensitive-attempt",Instant.now().plusSeconds(60),new TrialContextPayload(trial,"answer",JsonSupport.tree("{\"order_id\":\"A\"}"),Instant.now().plusSeconds(300)));
    }
    @Test void matchesSharedFixturesAndDeduplicatesAssignments() throws Exception {
        var root=Path.of(System.getProperty("contracts.dir"));
        for(var item:codec.tree(Files.readString(root.resolve("fixtures/v1/manifest.json")))) {
            var type=Class.forName("io.agenteval.protocol."+item.path("type").asText());
            String payload=Files.readString(root.resolve("fixtures/v1/"+item.path("file").asText()));
            if(item.path("valid").asBoolean()) assertThat(codec.decode(payload,type)).isNotNull();
            else assertThatThrownBy(() -> codec.decode(payload,type)).isInstanceOf(Exception.class);
        }
        var transport=new FakeTransport(); var calls=new AtomicInteger(); var assignment=grant(); var agent=UUID.randomUUID();
        try(var spool=new FileSpool(directory.resolve("events.sqlite")); var worker=new EvaluationWorker(transport,spool)) {
            worker.registerAgent(agent.toString(),context -> { calls.incrementAndGet(); return AgentResult.of(Map.of("amount",100)); });
            try(var executor=Executors.newFixedThreadPool(2)) {
                var first=executor.submit(() -> worker.execute(agent,assignment)); var second=executor.submit(() -> worker.execute(agent,assignment)); first.get(); second.get();
            }
            assertThat(calls.get()).isEqualTo(1); assertThat(transport.started.get()).isEqualTo(1);
            assertThat(transport.completions).hasSize(1);
            assertThat(transport.completions.getFirst().output().path("amount").asInt()).isEqualTo(100);
            assertThat(transport.completions.getFirst().eventCount()).isEqualTo(2);
        }
    }
    @Test void observesCancellationAndRejectsStaleStartedWithoutCallingBusiness() {
        var transport=new FakeTransport(); transport.cancel=true; var calls=new AtomicInteger(); var observed=new AtomicBoolean(); var agent=UUID.randomUUID();
        try(var spool=new FileSpool(directory.resolve("events.sqlite")); var worker=new EvaluationWorker(transport,spool)) {
            worker.registerAgent(agent.toString(),context -> { calls.incrementAndGet(); observed.set(context.cancellation().await(Duration.ofSeconds(1))); return AgentResult.of(42); });
            worker.execute(agent,grant());
            assertThat(observed.get()).isTrue();
            assertThat(transport.completions.getFirst().status()).isEqualTo(TrialExecutionStatus.CANCELLED);
            transport.reject=true; worker.execute(agent,grant()); assertThat(calls.get()).isEqualTo(1);
            assertThat(worker.activeHeartbeatCount()).isZero();
        }
    }
    @Test void slowUploadsDoNotBlockHeartbeats() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var renewed=new CountDownLatch(1); var business=new CountDownLatch(1);
        var transport=new FakeTransport() {
            @Override public Heartbeat heartbeat(LeaseGrant grant) { if(entered.getCount()==0) renewed.countDown(); return super.heartbeat(grant); }
            @Override public List<UUID> uploadEvents(LeaseGrant grant,List<TraceEvent> events) {
                entered.countDown(); try { release.await(3,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return super.uploadEvents(grant,events);
            }
        };
        var agent=UUID.randomUUID();
        try(var spool=new FileSpool(directory.resolve("slow.sqlite")); var worker=new EvaluationWorker(transport,spool); var executor=Executors.newSingleThreadExecutor()) {
            worker.registerAgent(agent.toString(),context -> { try { business.await(4,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); } return AgentResult.of(42); });
            var running=executor.submit(() -> worker.execute(agent,grant()));
            try { assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue(); assertThat(renewed.await(1,TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); business.countDown(); running.get(2,TimeUnit.SECONDS); }
        }
    }
    @Test void losingLeaseCancelsBusinessAndDoesNotSubmitSuccess() {
        var observed=new AtomicBoolean();
        var transport=new FakeTransport() { @Override public Heartbeat heartbeat(LeaseGrant grant) { throw new WorkerRejectedException(409); } };
        var agent=UUID.randomUUID();
        try(var spool=new FileSpool(directory.resolve("lost.sqlite")); var worker=new EvaluationWorker(transport,spool)) {
            worker.registerAgent(agent.toString(),context -> { observed.set(context.cancellation().await(Duration.ofSeconds(1))); return AgentResult.of(42); }); worker.execute(agent,grant());
            assertThat(observed.get()).isTrue(); assertThat(transport.completions).isEmpty(); assertThat(worker.activeHeartbeatCount()).isZero();
        }
    }
    @Test void assertionErrorRemainsFailureAndMarksRootSpanFailed() {
        var transport=new FakeTransport(); var agent=UUID.randomUUID();
        try(var spool=new FileSpool(directory.resolve("assertion.sqlite")); var worker=new EvaluationWorker(transport,spool)) {
            worker.registerAgent(agent.toString(),context -> { throw new AssertionError("private message"); });
            assertThatThrownBy(() -> worker.execute(agent,grant())).isInstanceOf(AssertionError.class);
            assertThat(transport.completions.getFirst().status()).isEqualTo(TrialExecutionStatus.FAILED);
            assertThat(transport.completions.getFirst().output().isNull()).isTrue();
            assertThat(transport.events).anyMatch(event -> event.eventType()==EventType.SPAN_FAILED);
        }
    }
}
