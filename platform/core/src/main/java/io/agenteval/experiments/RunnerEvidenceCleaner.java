package io.agenteval.experiments;
import java.util.UUID;
@FunctionalInterface
public interface RunnerEvidenceCleaner { void deleteEvidence(UUID jobId); }
