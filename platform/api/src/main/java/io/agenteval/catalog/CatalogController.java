package io.agenteval.catalog;
import io.agenteval.security.CurrentActor;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;
    private final DatasetService datasets;
    private final TemplateService templates;
    private final CurrentActor current;
    public CatalogController(CatalogService catalog,DatasetService datasets,TemplateService templates,CurrentActor current) { this.catalog=catalog; this.datasets=datasets; this.templates=templates; this.current=current; }
    public record CreateRequest(ResourceKind kind,String name,JsonNode content) {}
    public record UpdateRequest(long revision,String name,JsonNode content) {}
    public record ImportRequest(long revision,String jsonl) {}
    @GetMapping("/catalog") public List<CatalogResource> list(Authentication auth,@RequestParam ResourceKind kind,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="25") int limit) { return catalog.list(current.require(auth),kind,offset,limit); }
    @PostMapping("/catalog") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public CatalogResource create(Authentication auth,@RequestBody CreateRequest request) { return catalog.create(current.require(auth),request.kind(),request.name(),request.content()); }
    @GetMapping("/catalog/{id}") public CatalogResource get(Authentication auth,@PathVariable UUID id) { return catalog.draft(current.require(auth),id); }
    @PutMapping("/catalog/{id}") public CatalogResource update(Authentication auth,@PathVariable UUID id,@RequestBody UpdateRequest request) { return catalog.update(current.require(auth),id,request.revision(),request.name(),request.content()); }
    @DeleteMapping("/catalog/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void archive(Authentication auth,@PathVariable UUID id) { catalog.archive(current.require(auth),id); }
    @PostMapping("/catalog/{id}/versions") public PublishedVersion publish(Authentication auth,@PathVariable UUID id) { return catalog.publish(current.require(auth),id); }
    @GetMapping("/catalog/versions/{id}") public PublishedVersion version(Authentication auth,@PathVariable UUID id) { return catalog.version(current.require(auth),id); }
    @PostMapping("/catalog/{id}/import") public CatalogResource importJsonl(Authentication auth,@PathVariable UUID id,@RequestBody ImportRequest request) { return datasets.importJsonl(current.require(auth),id,request.revision(),request.jsonl()); }
    @GetMapping("/templates") public List<TemplateService.Template> templates() { return templates.list(); }
    @PostMapping("/templates/{id}/copy") public CatalogResource copy(Authentication auth,@PathVariable String id) { return templates.copy(current.require(auth),id); }
    @PostMapping("/templates/starter/copy") @org.springframework.transaction.annotation.Transactional
    public TemplateService.StarterBundle starter(Authentication auth) { return templates.copyStarter(current.require(auth)); }
}
