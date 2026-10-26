package io.agenteval.reporting;

import io.agenteval.evaluation.HumanReview;
import io.agenteval.identity.WorkspaceActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public interface ComparisonQueries {
    ReportData load(WorkspaceActor actor, UUID experiment);
    Pair pair(WorkspaceActor actor, UUID left, UUID right);
    ExportInput export(WorkspaceActor actor, UUID experiment);
    record Input(ReportData data, ReportProvenance provenance) {}
    record Pair(Input left, Input right) {}
    record FrozenEvidence(UUID trialId, UUID snapshotId, String snapshotHash, String completeness, JsonNode manifest) {
        public FrozenEvidence { manifest=manifest.deepCopy(); }
        @Override public JsonNode manifest() { return manifest.deepCopy(); }
    }
    record ResultIdentity(UUID resultId, UUID runId, UUID trialId, UUID evaluatorVersion) {}
    record ExportInput(Input input, List<HumanReview> reviews, List<FrozenEvidence> evidence, List<ResultIdentity> resultIdentities) {
        public ExportInput { reviews=List.copyOf(reviews); evidence=List.copyOf(evidence); resultIdentities=List.copyOf(resultIdentities); }
    }
}
