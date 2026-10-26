package io.agenteval.reporting;

import io.agenteval.security.CurrentActor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public final class ReportComparisonController {
    private final ComparisonService comparisons;
    private final ReportExporter exporter;
    private final CurrentActor current;
    public ReportComparisonController(ComparisonService comparisons, ReportExporter exporter, CurrentActor current) { this.comparisons=comparisons; this.exporter=exporter; this.current=current; }
    @GetMapping("/comparison") public ComparisonReport compare(Authentication auth, @RequestParam UUID left, @RequestParam UUID right) { return comparisons.compare(current.require(auth),left,right); }
    @GetMapping("/experiments/{id}/export") public ResponseEntity<ReportExporter.Export> export(Authentication auth,@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=agenteval-"+id+".json").body(exporter.export(current.require(auth),id));
    }
}
