package io.agenteval.reporting;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/reports/experiments")
public class ReportController {
    private final ReportService reports;
    private final CurrentActor current;
    public ReportController(ReportService reports,CurrentActor current) { this.reports=reports; this.current=current; }
    @GetMapping("/{id}") public ExperimentReport get(Authentication auth,@PathVariable UUID id) { return reports.get(current.require(auth),id); }
    @GetMapping("/{id}/trials") public ReportService.Page trials(Authentication auth,@PathVariable UUID id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="25") int limit) { return reports.trials(current.require(auth),id,cursor,limit); }
}
