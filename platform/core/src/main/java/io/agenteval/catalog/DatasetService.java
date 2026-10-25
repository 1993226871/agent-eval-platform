package io.agenteval.catalog;
import io.agenteval.identity.WorkspaceActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

public final class DatasetService {
    private final CatalogService catalog;
    private final ObjectMapper json;
    public DatasetService(CatalogService catalog,ObjectMapper json) { this.catalog=catalog; this.json=json; }
    public PublishedVersion publishDataset(WorkspaceActor actor,UUID id) { requireDataset(actor,id); return catalog.publish(actor,id); }
    public CatalogResource importJsonl(WorkspaceActor actor,UUID id,long revision,String source) {
        var draft=requireDataset(actor,id);
        if(source==null || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1024*1024) throw new IllegalArgumentException("Import exceeds 2 MiB");
        var tasks=json.createArrayNode(); String[] lines=source.split("\\R",-1);
        for(int i=0;i<lines.length;i++) {
            if(lines[i].isBlank()) continue;
            try { tasks.add(json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(lines[i])); }
            catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid JSONL at line "+(i+1)); }
        }
        var content=json.createObjectNode().set("cases",tasks); CatalogService.validateDataset(content);
        return catalog.update(actor,id,revision,draft.name(),content);
    }
    private CatalogResource requireDataset(WorkspaceActor actor,UUID id) {
        var draft=catalog.draft(actor,id); if(draft.kind()!=ResourceKind.DATASET) throw new IllegalArgumentException("Expected dataset"); return draft;
    }
}
