package io.agenteval.reporting;

import java.util.List;
import java.util.UUID;

public record ReportProvenance(List<String> evaluatorVersions, List<String> modelPriceBindings, List<Evidence> evidence) {
    public ReportProvenance { evaluatorVersions=List.copyOf(evaluatorVersions); modelPriceBindings=List.copyOf(modelPriceBindings); evidence=List.copyOf(evidence); }
    public record Evidence(UUID trialId, int caseIndex, int attempt, UUID snapshotId, String snapshotHash, String completeness, String scope) {}
}
