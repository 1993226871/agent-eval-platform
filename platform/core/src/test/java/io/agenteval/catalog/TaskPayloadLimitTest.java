package io.agenteval.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenteval.agents.*;
import io.agenteval.experiments.*;
import io.agenteval.identity.WorkspaceActor;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskPayloadLimitTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void rejectsSingleCaseThatCannotSafelyFitSdkLeaseResponse() {
        var content=json.createObjectNode(); content.putArray("cases").addObject().put("instruction","Check").put("input","x".repeat(800*1024));
        assertThatThrownBy(() -> CatalogService.validateDataset(content)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void boundsFrozenConfigurationBeforeCollectingAllEvaluatorBodies() throws Exception {
        var actor=new WorkspaceActor(UUID.randomUUID(),UUID.randomUUID(),false); UUID agent=UUID.randomUUID(),dataset=UUID.randomUUID(),suite=UUID.randomUUID();
        var catalog=mock(CatalogService.class); var agents=mock(AgentService.class); var repository=mock(ExperimentRepository.class);
        var datasetContent=json.readTree("{\"cases\":[{\"instruction\":\"Check\",\"input\":{}}]}");
        var suiteContent=json.createObjectNode(); var entries=suiteContent.putArray("entries");
        for(int i=0;i<100;i++) entries.addObject().put("evaluator_version",UUID.randomUUID().toString()).put("dimension","outcome").put("required_to_pass",true).put("weight",1);
        var large=json.createObjectNode().put("type","PYTHON").put("criterion_id","custom").put("threshold",1); large.putArray("required_evidence").add("output"); large.putObject("config").put("code","x".repeat(1900000));
        var fetched=new AtomicInteger();
        when(catalog.version(eq(actor),any(UUID.class))).thenAnswer(call -> {
            UUID id=call.getArgument(1); if(id.equals(dataset)) return new PublishedVersion(id,UUID.randomUUID(),ResourceKind.DATASET,"Dataset",datasetContent,"a".repeat(64),1);
            if(id.equals(suite)) return new PublishedVersion(id,UUID.randomUUID(),ResourceKind.SUITE,"Suite",suiteContent,"a".repeat(64),1);
            fetched.incrementAndGet(); return new PublishedVersion(id,UUID.randomUUID(),ResourceKind.EVALUATOR,"Evaluator",large,"a".repeat(64),1);
        });
        when(repository.replay(eq(actor),anyString(),anyString())).thenReturn(Optional.empty());
        var service=new ExperimentService(repository,catalog,agents,Clock.systemUTC());
        assertThatThrownBy(() -> service.start(actor,new StartExperimentCommand(agent,"v1",dataset,suite,1,Instant.now().plusSeconds(3600),"snapshot-limit"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(fetched.get()).isLessThan(10);
        verify(repository,never()).start(any(),any(),anyString(),any(),any());
    }
    @Test void boundsReevaluationConfigurationBeforeFreezingEvidence() {
        var actor=new WorkspaceActor(UUID.randomUUID(),UUID.randomUUID(),false); UUID suite=UUID.randomUUID();
        var catalog=mock(CatalogService.class); var repository=mock(io.agenteval.evaluation.ReevaluationRepository.class); var evidence=mock(io.agenteval.telemetry.EvidenceService.class);
        var suiteContent=json.createObjectNode(); var entries=suiteContent.putArray("entries");
        for(int i=0;i<100;i++) entries.addObject().put("evaluator_version",UUID.randomUUID().toString()).put("dimension","outcome").put("required_to_pass",true).put("weight",1);
        var large=json.createObjectNode().put("type","PYTHON").put("criterion_id","custom").put("threshold",1); large.putArray("required_evidence").add("output"); large.putObject("config").put("code","x".repeat(1900000));
        var fetched=new AtomicInteger();
        when(catalog.version(eq(actor),any(UUID.class))).thenAnswer(call -> { UUID id=call.getArgument(1); if(id.equals(suite)) return new PublishedVersion(id,UUID.randomUUID(),ResourceKind.SUITE,"Suite",suiteContent,"a".repeat(64),1); fetched.incrementAndGet(); return new PublishedVersion(id,UUID.randomUUID(),ResourceKind.EVALUATOR,"Evaluator",large,"a".repeat(64),1); });
        var service=new io.agenteval.evaluation.ReevaluationService(repository,catalog,evidence,Clock.systemUTC());
        assertThatThrownBy(() -> service.create(actor,UUID.randomUUID(),suite,false,"bounded-regrade")).isInstanceOf(IllegalArgumentException.class);
        assertThat(fetched.get()).isLessThan(10); verifyNoInteractions(repository,evidence);
    }
}
