package io.agenteval.telemetry;
import io.agenteval.protocol.*;
import io.agenteval.workers.*;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.nio.*;
import java.nio.charset.*;

@RestController
@RequestMapping("/api/v1")
public class TelemetryController {
    private final TelemetryService telemetry;
    private final CurrentActor current;
    private final ProtocolCodec codec=new ProtocolCodec();
    public TelemetryController(TelemetryService telemetry,CurrentActor current) { this.telemetry=telemetry; this.current=current; }
    @PostMapping("/trials/{id}/events:batch")
    public BatchAck ingest(Authentication auth,@PathVariable UUID id,@RequestHeader("X-Attempt-Token") String token,@RequestBody byte[] raw) {
        if(!(auth.getPrincipal() instanceof WorkerPrincipal principal)) throw new InvalidWorkerCredentialException();
        if(raw.length>1024*1024) throw new TelemetryLimitException();
        try {
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var body=codec.tree(decoder.decode(ByteBuffer.wrap(raw)).toString());
            if(!body.isObject() || body.size()!=1 || !body.path("events").isArray()) throw new IllegalArgumentException("Expected events array");
            if(body.get("events").size()>100) throw new TelemetryLimitException();
            var events=new ArrayList<TraceEvent>();
            for(var event:body.get("events")) events.add(codec.decode(event.toString(),TraceEvent.class));
            return telemetry.ingest(principal,id,token,events);
        } catch(java.io.IOException malformed) { throw new IllegalArgumentException("Invalid UTF-8 trace message"); }
    }
    @GetMapping("/traces/{id}") public TracePage query(Authentication auth,@PathVariable UUID id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return telemetry.queryTrialTrace(current.require(auth),id,cursor,limit);
    }
}
