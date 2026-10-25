package io.agenteval.sdk;
import io.agenteval.protocol.LeaseGrant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
class ArtifactCaptureTest {
    @TempDir Path directory;
    @Test void preservesBusinessOutputWhileAttachingLargeRedactedEvidence() {
        var captured=new CopyOnWriteArrayList<String>();
        var transport=new WorkerContractTest.FakeTransport() {
            @Override public UUID uploadBytes(LeaseGrant grant,byte[] content,String name,String mediaType,String idempotencyKey) { captured.add(new String(content,StandardCharsets.UTF_8)); return UUID.randomUUID(); }
        };
        var agent=UUID.randomUUID(); var grant=WorkerContractTest.grant();
        try(var spool=new FileSpool(directory.resolve("payload.sqlite")); var worker=new EvaluationWorker(transport,spool)) {
            worker.registerAgent(agent.toString(),context -> AgentResult.of(Telemetry.tool("model",() -> Map.of("text","x".repeat(100000),"api_key","private-secret"))));
            worker.execute(agent,grant);
            assertThat(captured).isNotEmpty().allMatch(value -> !value.contains("private-secret"));
            assertThat(captured).anyMatch(value -> value.contains("x".repeat(100000)));
            assertThat(transport.completions.getFirst().artifactIds()).isNotEmpty();
            assertThat(transport.completions.getFirst().output().path("api_key").asText()).isEqualTo("private-secret");
            assertThat(spool.pendingPayloads(grant.trialId())).isEmpty();
        }
    }
    @Test void explicitlySelectedFilesJoinTheCompletionManifest() throws Exception {
        var selected=directory.resolve("report.txt"); Files.writeString(selected,"fixed evidence"); var uploaded=UUID.randomUUID();
        var transport=new WorkerContractTest.FakeTransport() {
            @Override public UUID uploadBytes(LeaseGrant grant,byte[] content,String name,String mediaType,String idempotencyKey) { assertThat(name).isEqualTo("report.txt"); assertThat(new String(content,StandardCharsets.UTF_8)).isEqualTo("fixed evidence"); return uploaded; }
        };
        var agent=UUID.randomUUID();
        try(var spool=new FileSpool(directory.resolve("file.sqlite")); var worker=new EvaluationWorker(transport,spool)) {
            worker.registerAgent(agent.toString(),context -> { context.artifact(selected,"report.txt","text/plain"); return AgentResult.of(42); });
            worker.execute(agent,WorkerContractTest.grant());
            assertThat(transport.completions.getFirst().status()).isEqualTo(io.agenteval.protocol.TrialExecutionStatus.SUCCEEDED);
            assertThat(transport.completions.getFirst().artifactIds()).containsExactly(uploaded);
        }
    }
}
