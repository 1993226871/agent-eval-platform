package io.agenteval.reporting;
import io.agenteval.identity.WorkspaceActor;
import java.util.UUID;
public interface ReportRepository { ReportData load(WorkspaceActor actor,UUID experimentId); }
