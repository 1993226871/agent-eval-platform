package io.agenteval.infrastructure.evaluation;

import com.sun.net.httpserver.HttpServer;
import io.agenteval.evaluation.*;
import io.agenteval.identity.WorkspaceActor;
import io.agenteval.protocol.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class RunnerSandboxClientTest {
    private HttpServer server;
    private final ProtocolCodec codec = new ProtocolCodec();
    private final UUID trial = UUID.randomUUID(), snapshot = UUID.randomUUID(), version = UUID.randomUUID();
    private final String image = "sha256:" + "a".repeat(64), key = "s".repeat(40);
    private String request, authorization, response;
    private int status = 200;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/evaluations", exchange -> {
            request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    EvaluatorJob job() throws Exception {
        return new EvaluatorJob(UUID.randomUUID(), UUID.randomUUID(), trial, snapshot,
            new WorkspaceActor(UUID.randomUUID(), UUID.randomUUID(), false),
            new EvaluatorDefinition(version, "accuracy", "PYTHON", List.of(), new BigDecimal("0.8"), codec.tree("{\"code\":\"def evaluate(context): return {}\"}")), UUID.randomUUID(), Instant.now().plusSeconds(60));
    }
    EvaluationContext context() throws Exception {
        return new EvaluationContext(trial, snapshot, codec.tree("{}"), codec.tree("{}"), codec.tree("{}"), List.of(), List.of(), EvidenceCompleteness.NONE);
    }
    RunnerSandboxClient client() {
        return new RunnerSandboxClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()), key, image, (job, context) -> List.of());
    }
    String envelope(EvaluatorJob job, String result) {
        return "{\"job_id\":\""+job.id()+"\",\"state\":\"COMPLETED\",\"code\":\"COMPLETED\",\"runtime\":\"runsc\",\"image_digest\":\""+image+"\",\"result\":"+result+"}";
    }
    @Test void bindsJobImageThresholdAndEvidence() throws Exception {
        var job = job();
        response = envelope(job, "{\"status\":\"SCORED\",\"score\":0.75,\"reason\":\"Compared\",\"evidence_refs\":[\"output\"]}");
        var result = client().evaluate(job, context());
        assertThat(result.status()).isEqualTo(CriterionStatus.SCORED);
        assertThat(result.passed()).isFalse();
        assertThat(result.evaluatorVersion()).isEqualTo(version);
        assertThat(result.criterionId()).isEqualTo("accuracy");
        assertThat(authorization).isEqualTo("Bearer "+key);
        var sent = codec.tree(request);
        assertThat(sent.path("job_id").asText()).isEqualTo(job.id().toString());
        assertThat(sent.path("code_hash").asText()).hasSize(64);
        assertThat(sent.path("limits").path("timeout_seconds").asInt()).isEqualTo(30);
        assertThat(request).doesNotContain(key);
    }
    @Test void unavailableIsUnscoredAndBusyRemainsRetryable() throws Exception {
        var job = job(); status=503;
        response="{\"job_id\":\""+job.id()+"\",\"state\":\"UNAVAILABLE\",\"code\":\"CAPABILITY_UNAVAILABLE\",\"result\":null}";
        assertThat(client().evaluate(job, context()).status()).isEqualTo(CriterionStatus.SKIPPED);
        status=429; response="{}";
        assertThatThrownBy(() -> client().evaluate(job, context())).isInstanceOf(SandboxClient.RetryableException.class);
    }
    @Test void forgedReferencesAndMismatchedExecutionCannotScore() throws Exception {
        var job = job();
        response=envelope(job,"{\"status\":\"SCORED\",\"score\":1,\"reason\":\"bad\",\"evidence_refs\":[\"event:"+UUID.randomUUID()+"\"]}");
        assertThat(client().evaluate(job,context()).status()).isEqualTo(CriterionStatus.ERROR);
        response=envelope(job(),"{\"status\":\"SCORED\",\"score\":1,\"reason\":\"bad\",\"evidence_refs\":[]}");
        assertThat(client().evaluate(job,context()).status()).isEqualTo(CriterionStatus.ERROR);
    }
}
