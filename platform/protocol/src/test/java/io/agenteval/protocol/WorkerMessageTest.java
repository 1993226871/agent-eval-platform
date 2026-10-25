package io.agenteval.protocol;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class WorkerMessageTest {
    private final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ProtocolCodec codec=new ProtocolCodec();

    @Test void boundsClaimWaitAndRequiresWorkerIdentity() throws Exception {
        assertThatThrownBy(() -> new ClaimRequest(id,id,"v1",21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimRequest(null,id,"v1",20)).isInstanceOf(NullPointerException.class);
        var request=new ClaimRequest(id,id,"v1",20);
        assertThat(codec.decode(codec.encode(request),ClaimRequest.class)).isEqualTo(request);
    }
    @Test void preventsLeaseAssignmentToDifferentTrial() throws Exception {
        var task=new TrialContextPayload(id,"query",codec.tree("{}"),Instant.parse("2026-10-25T10:00:00Z"));
        assertThatThrownBy(() -> new LeaseGrant(UUID.randomUUID(),"11111111111111111111111111111111","token",
            Instant.parse("2026-10-25T09:01:00Z"),task)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void completionHasTerminalStateAndConsistentCounts() throws Exception {
        assertThatThrownBy(() -> new CompletionRequest(TrialExecutionStatus.RUNNING,codec.tree("{}"),2,2,0,List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompletionRequest(TrialExecutionStatus.SUCCEEDED,codec.tree("{}"),2,3,0,List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        var complete=new CompletionRequest(TrialExecutionStatus.SUCCEEDED,codec.tree("{\"ok\":true}"),2,2,0,List.of());
        assertThat(codec.decode(codec.encode(complete),CompletionRequest.class)).isEqualTo(complete);
    }
    @Test void evidenceArtifactsUseIdsAndHashesRatherThanArbitraryPaths() throws Exception {
        assertThatThrownBy(() -> new ArtifactRef(id,"report.txt","text/plain",-1,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactRef(id,"report.txt","text/plain",1,"not-a-hash")).isInstanceOf(IllegalArgumentException.class);
        var artifact=new ArtifactRef(id,"report.txt","text/plain",1,"a".repeat(64));
        assertThat(codec.tree(codec.encode(artifact)).has("path")).isFalse();
    }
    @Test void evaluationEvidenceIsDefensivelyCopied() throws Exception {
        var input=codec.tree("{\"amount\":100}");
        var context=new EvaluationContext(id,id,input,codec.tree("{}"),codec.tree("{}"),List.of(),List.of(),EvidenceCompleteness.COMPLETE);
        ((com.fasterxml.jackson.databind.node.ObjectNode)input).put("amount",0);
        assertThat(context.taskInput().get("amount").asInt()).isEqualTo(100);
        assertThat(codec.decode(codec.encode(context),EvaluationContext.class)).isEqualTo(context);
    }
}
