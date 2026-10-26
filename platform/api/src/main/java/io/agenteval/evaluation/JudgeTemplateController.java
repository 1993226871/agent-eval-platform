package io.agenteval.evaluation;
import io.agenteval.catalog.CatalogResource;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/templates/judges")
public class JudgeTemplateController {
    private final JudgeTemplateService templates;
    private final CurrentActor current;
    public JudgeTemplateController(JudgeTemplateService templates,CurrentActor current) { this.templates=templates; this.current=current; }
    public record Copy(UUID modelProfileId,UUID priceVersionId) {}
    @GetMapping public List<JudgeTemplateService.Template> list(Authentication auth) { current.require(auth); return templates.list(); }
    @PostMapping("/{id}/copy") public CatalogResource copy(Authentication auth,@PathVariable String id,@RequestBody Copy command) { return templates.copy(current.require(auth),id,command.modelProfileId(),command.priceVersionId()); }
}
