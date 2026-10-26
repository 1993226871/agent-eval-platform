package io.agenteval.reporting;

import java.math.BigDecimal;
import java.util.*;

public record ComparisonReport(ExperimentReport left, ExperimentReport right, ReportProvenance leftProvenance,
    ReportProvenance rightProvenance, boolean directlyComparable, List<String> reasons, Map<String,BigDecimal> dimensionDeltas) {
    public ComparisonReport { reasons=List.copyOf(reasons); dimensionDeltas=Map.copyOf(dimensionDeltas); }
}
