package io.agenteval.infrastructure.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.evaluation.*;
import io.agenteval.protocol.*;
import io.agenteval.http.LimitedBodySubscriber;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Internal transport only. Never executes user code in the Java service. */
public final class RunnerSandboxClient implements SandboxClient, io.agenteval.experiments.RunnerEvidenceCleaner, AutoCloseable {
    public record ArtifactDownload(UUID artifactId, String url) {}
    @FunctionalInterface public interface ArtifactResolver {
        // Resolve ONLY artifacts of this owner/trial/frozen snapshot; URLs are short lived.
        List<ArtifactDownload> resolve(EvaluatorJob job, EvaluationContext context);
    }
    private final URI endpoint;
    private final String key, image;
    private final ArtifactResolver artifacts;
    private final ProtocolCodec codec = new ProtocolCodec();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public RunnerSandboxClient(URI base, String key, String image, ArtifactResolver artifacts) {
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null
            || base.getQuery()!=null || base.getFragment()!=null || !(base.getPath().isEmpty() || base.getPath().equals("/"))
            || key==null || key.length()<32 || key.contains("\n") || key.contains("\r")
            || image==null || !image.matches("(?:sha256:[0-9a-f]{64}|[a-zA-Z0-9./:_-]+@sha256:[0-9a-f]{64})"))
            throw new IllegalArgumentException("Invalid internal Runner configuration");
        this.endpoint=base.resolve("/internal/v1/evaluations"); this.key=key; this.image=image; this.artifacts=Objects.requireNonNull(artifacts);
    }

    @Override public CriterionResult evaluate(EvaluatorJob job, EvaluationContext context) {
        if (!job.trialId().equals(context.trialId()) || !job.snapshotId().equals(context.snapshotId()) || !job.definition().type().equals("PYTHON"))
            throw new IllegalArgumentException("Sandbox context does not match evaluator job");
        var definition=job.definition();
        var code=definition.config().path("code");
        if (!code.isTextual() || code.asText().isBlank() || code.asText().getBytes(StandardCharsets.UTF_8).length>65536)
            return unscored(job, CriterionStatus.ERROR, "INVALID_PYTHON_CODE");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.asText().getBytes(StandardCharsets.UTF_8)));
            var downloads=List.copyOf(artifacts.resolve(job, context));
            var expectedIds=new HashSet<UUID>(); context.artifacts().forEach(item -> expectedIds.add(item.artifactId()));
            var actualIds=new HashSet<UUID>(); downloads.forEach(item -> actualIds.add(item.artifactId()));
            if (!expectedIds.equals(actualIds) || actualIds.size()!=downloads.size()) throw new IllegalArgumentException("Artifact scope mismatch");
            String body=codec.encode(Map.of("job_id",job.id(), "code",code.asText(), "code_hash",hash, "image_digest",image,
                "context",context, "limits",Map.of("timeout_seconds",30,"memory_mb",256,"processes",32,"tmp_mb",64), "artifact_downloads",downloads));
            if(body.getBytes(StandardCharsets.UTF_8).length>3*1024*1024) return unscored(job,CriterionStatus.ERROR,"RUNNER_REQUEST_LIMIT");
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(58)).header("Authorization","Bearer "+key)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var future=http.sendAsync(request, info -> new LimitedBodySubscriber(2*1024*1024));
            HttpResponse<byte[]> response;
            try { response=future.get(58,TimeUnit.SECONDS); }
            catch(Exception failure) { future.cancel(true); if(failure instanceof InterruptedException) Thread.currentThread().interrupt(); throw new RetryableException("Runner response unavailable; query using the same job ID"); }
            int status=response.statusCode();
            if(status==202 || status==429 || status==502 || status==504) throw new RetryableException("Runner execution pending; retry the same job ID");
            JsonNode envelope=codec.tree(new String(response.body(),StandardCharsets.UTF_8));
            if(status==422 && Set.of("SYNTAX_ERROR","INVALID_CODE_HASH_OR_SIZE","RESOURCE_LIMIT","RESOURCE_LIMIT_TOO_LOW").contains(envelope.path("code").asText()))
                return unscored(job,CriterionStatus.ERROR,"PYTHON_"+envelope.path("code").asText());
            if(!envelope.path("job_id").asText().equals(job.id().toString())) throw new IllegalArgumentException("Runner job mismatch");
            if(status==503 && envelope.path("code").asText().equals("CAPABILITY_UNAVAILABLE") && envelope.path("state").asText().equals("UNAVAILABLE"))
                return unscored(job,CriterionStatus.SKIPPED,"CAPABILITY_UNAVAILABLE");
            if(status!=200) return unscored(job,CriterionStatus.ERROR,"RUNNER_REQUEST_REJECTED");
            if(!envelope.path("state").asText().equals("COMPLETED")) {
                String errorCode=envelope.path("code").asText();
                return unscored(job,CriterionStatus.ERROR,Set.of("TIMEOUT","OUT_OF_MEMORY","NO_RESULT","PROCESS_ERROR","PROCESS_EXIT_TIMEOUT","EXECUTION_UNKNOWN","RESULT_LIMIT","INVALID_RESULT_JSON","INVALID_RESULT_FIELDS","INVALID_SCORE","UNKNOWN_EVIDENCE_REFERENCE","SANDBOX_EXECUTION_FAILED").contains(errorCode)?"PYTHON_"+errorCode:"PYTHON_EXECUTION_FAILED");
            }
            if(!envelope.path("runtime").asText().equals("runsc") || !envelope.path("image_digest").asText().equals(image)
                || !envelope.path("code").asText().equals("COMPLETED")) throw new IllegalArgumentException("Unapproved execution identity");
            JsonNode raw=envelope.path("result");
            var fields=new HashSet<String>(); raw.fieldNames().forEachRemaining(fields::add);
            if(!fields.equals(Set.of("status","score","reason","evidence_refs"))) throw new IllegalArgumentException("Invalid result fields");
            var resultStatus=CriterionStatus.valueOf(raw.path("status").asText());
            if(resultStatus==CriterionStatus.WAITING_QUOTA) throw new IllegalArgumentException("Invalid Python status");
            var reason=raw.path("reason");
            if(!reason.isTextual() || reason.asText().isBlank() || reason.asText().length()>2048 || !raw.path("evidence_refs").isArray()) throw new IllegalArgumentException("Invalid result reason or references");
            var refs=new ArrayList<String>();
            for(var ref:raw.path("evidence_refs")) { if(!ref.isTextual()) throw new IllegalArgumentException("Invalid reference"); refs.add(ref.asText()); }
            new EvidenceReferenceValidator().validate(context,refs);
            var score=raw.path("score");
            if(resultStatus==CriterionStatus.SCORED && !score.isNumber() || resultStatus!=CriterionStatus.SCORED && !score.isNull()) throw new IllegalArgumentException("Invalid score type");
            return new CriterionResult(definition.criterionId(),resultStatus,resultStatus==CriterionStatus.SCORED?score.decimalValue():null,
                resultStatus==CriterionStatus.SCORED?score.decimalValue().compareTo(definition.threshold())>=0:null,reason.asText(),refs,definition.evaluatorVersion());
        } catch(RetryableException failure) { throw failure; }
        catch(Exception invalid) { return unscored(job,CriterionStatus.ERROR,"INVALID_RUNNER_RESULT_OR_REQUEST"); }
    }
    private CriterionResult unscored(EvaluatorJob job, CriterionStatus status, String reason) {
        return new CriterionResult(job.definition().criterionId(),status,null,null,reason,List.of(),job.definition().evaluatorVersion());
    }
    @Override public void deleteEvidence(UUID job) {
        var request=HttpRequest.newBuilder(URI.create(endpoint.toString()+"/"+job+"/evidence")).timeout(Duration.ofSeconds(90))
            .header("Authorization","Bearer "+key).DELETE().build();
        var future=http.sendAsync(request,info -> new LimitedBodySubscriber(4096));
        try { if(future.get(90,TimeUnit.SECONDS).statusCode()!=204) throw new IllegalStateException("Runner cleanup rejected"); }
        catch(Exception unavailable) { future.cancel(true); if(unavailable instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IllegalStateException("Runner cleanup unavailable"); }
    }
    @Override public void close() { http.shutdownNow(); }
}
