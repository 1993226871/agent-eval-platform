package io.agenteval.experiments;
import io.agenteval.agents.*;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1")
public class ExperimentController {
    private final ExperimentService experiments;
    private final AgentService agents;
    private final CurrentActor current;
    public ExperimentController(ExperimentService experiments,AgentService agents,CurrentActor current) { this.experiments=experiments; this.agents=agents; this.current=current; }
    public record AgentCreate(String name,String version) {}
    @PostMapping("/agents") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public AgentRecord createAgent(Authentication auth,@RequestBody AgentCreate request) { return agents.create(current.require(auth),request.name(),request.version()); }
    @GetMapping("/agents") public List<AgentRecord> agents(Authentication auth) { return agents.list(current.require(auth)); }
    @PostMapping("/experiments") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String,UUID> start(Authentication auth,@RequestBody StartExperimentCommand request) { return Map.of("id",experiments.start(current.require(auth),request)); }
    @GetMapping("/experiments/estimate")
    public com.fasterxml.jackson.databind.JsonNode estimate(Authentication auth,@RequestParam UUID datasetVersionId,@RequestParam UUID suiteVersionId,@RequestParam int repeatCount) {
        return experiments.estimate(current.require(auth),datasetVersionId,suiteVersionId,repeatCount);
    }
    @GetMapping("/experiments") public List<ExperimentRecord> list(Authentication auth,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) { return experiments.list(current.require(auth),offset,limit); }
    @GetMapping("/experiments/{id}") public ExperimentRecord get(Authentication auth,@PathVariable UUID id) { return experiments.get(current.require(auth),id); }
    @GetMapping("/experiments/{id}/trials") public List<TrialRecord> trials(Authentication auth,@PathVariable UUID id) { return experiments.trials(current.require(auth),id); }
    @PostMapping("/experiments/{id}/cancel") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancel(Authentication auth,@PathVariable UUID id) { experiments.cancel(current.require(auth),id); }
}
