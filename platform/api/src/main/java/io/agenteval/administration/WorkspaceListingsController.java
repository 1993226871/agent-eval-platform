package io.agenteval.administration;

import io.agenteval.catalog.ResourceKind;
import io.agenteval.security.CurrentActor;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceListingsController {
    private final WorkspaceListings listings;
    private final CurrentActor current;
    public WorkspaceListingsController(WorkspaceListings listings,CurrentActor current) { this.listings=listings; this.current=current; }
    @GetMapping("/catalog-summaries") public List<WorkspaceListings.ResourceSummary> catalog(Authentication auth,@RequestParam ResourceKind kind,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) { return listings.catalog(current.require(auth),kind,offset,limit); }
    @GetMapping("/experiment-summaries") public List<WorkspaceListings.ExperimentSummary> experiments(Authentication auth,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) { return listings.experiments(current.require(auth),offset,limit); }
    @GetMapping("/experiment-summaries/{id}") public WorkspaceListings.ExperimentSummary experiment(Authentication auth,@PathVariable UUID id) { return listings.experiment(current.require(auth),id); }
}
