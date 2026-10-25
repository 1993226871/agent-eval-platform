package io.agenteval.telemetry;
import io.agenteval.protocol.*;
import io.agenteval.workers.*;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1")
public class ArtifactController {
    private final ArtifactService artifacts;
    private final EvidenceService evidence;
    private final CurrentActor current;
    public ArtifactController(ArtifactService artifacts,EvidenceService evidence,CurrentActor current) { this.artifacts=artifacts; this.evidence=evidence; this.current=current; }
    @PostMapping("/trials/{trial}/artifacts")
    public ArtifactUploadGrant initialize(Authentication auth,@PathVariable UUID trial,@RequestHeader("X-Attempt-Token") String token,@RequestBody RegisterArtifactRequest request) {
        return artifacts.initialize(worker(auth),trial,token,request);
    }
    @PostMapping("/trials/{trial}/artifacts/{id}/complete")
    public ArtifactRef complete(Authentication auth,@PathVariable UUID trial,@PathVariable UUID id,@RequestHeader("X-Attempt-Token") String token) {
        return artifacts.complete(worker(auth),trial,token,id);
    }
    @GetMapping("/artifacts/{id}/download") public Download download(Authentication auth,@PathVariable UUID id) { return new Download(artifacts.download(current.require(auth),id)); }
    @PostMapping("/evidence/trials/{trial}/freeze")
    public EvidenceSnapshot freeze(Authentication auth,@PathVariable UUID trial,@RequestParam(defaultValue="false") boolean includeLate) { return evidence.freeze(current.require(auth),trial,includeLate); }
    @GetMapping("/evidence/snapshots/{id}") public EvidenceSnapshot load(Authentication auth,@PathVariable UUID id) { return evidence.load(current.require(auth),id); }
    private WorkerPrincipal worker(Authentication auth) { if(!(auth.getPrincipal() instanceof WorkerPrincipal p)) throw new InvalidWorkerCredentialException(); return p; }
    public record Download(URI url) {}
}
