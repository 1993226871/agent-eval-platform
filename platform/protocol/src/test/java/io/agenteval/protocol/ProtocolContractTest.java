package io.agenteval.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ProtocolContractTest {
    private final ProtocolCodec codec = new ProtocolCodec();
    private final UUID trialId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void workerPayloadDoesNotExposeReferenceOrSecrets() throws Exception {
        var task = new TrialContextPayload(trialId, "为订单退款", codec.tree("{\"order_id\":\"A\"}"), Instant.parse("2026-10-25T09:00:00Z"));
        JsonNode wire = codec.tree(codec.encode(task));
        assertThat(wire.has("expected")).isFalse();
        assertThat(wire.has("evaluator_code")).isFalse();
        assertThat(wire.has("model_key")).isFalse();
        assertThat(wire.get("instruction").asText()).isEqualTo("为订单退款");
        assertThat(wire.get("trial_id").asText()).isEqualTo(trialId.toString());
    }

    @ParameterizedTest @ValueSource(strings={"-0.1", "1.1"})
    void rejectsScoreOutsideUnitInterval(String score) {
        assertThatThrownBy(() -> new CriterionResult("amount", CriterionStatus.SCORED,
            new BigDecimal(score), true, "金额", List.of("event:1"), trialId))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsScoreOnInsufficientEvidence() {
        assertThatThrownBy(() -> new CriterionResult("amount", CriterionStatus.INSUFFICIENT_EVIDENCE,
            BigDecimal.ZERO, false, "缺少退款事件", List.of(), trialId))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMissingScoreOnScoredResult() {
        assertThatThrownBy(() -> new CriterionResult("amount", CriterionStatus.SCORED,
            null, true, "缺少分数", List.of(), trialId))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings={"NaN", "Infinity", "\"NaN\""})
    void rejectsNonFiniteWireScore(String score) {
        String wire = "{\"criterion_id\":\"amount\",\"status\":\"SCORED\",\"score\":" + score
            + ",\"passed\":true,\"reason\":\"ok\",\"evidence_refs\":[],\"evaluator_version\":\""+trialId+"\"}";
        assertThatThrownBy(() -> codec.decode(wire, CriterionResult.class)).isInstanceOf(Exception.class);
    }

    @Test void roundTripsRootSpanAndNestedUnicode() throws Exception {
        var event = validEvent();
        var decoded = codec.decode(codec.encode(event), TraceEvent.class);
        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.parentSpanId()).isNull();
        assertThat(decoded.payload().at("/input/订单").asText()).isEqualTo("A");
    }

    @Test void rejectsUnknownProtocolAndMalformedIdentifiers() throws Exception {
        String wire = codec.encode(validEvent());
        assertThatThrownBy(() -> codec.decode(wire.replace("\"1.0\"", "\"2.0\""), TraceEvent.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> codec.decode(wire.replace("11111111111111111111111111111111", "invalid"), TraceEvent.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> codec.decode(wire.replace(trialId.toString(), "invalid"), TraceEvent.class)).isInstanceOf(Exception.class);
    }

    @Test void rejectsUnknownFieldsAndNonPositiveSequence() throws Exception {
        String wire = codec.encode(validEvent());
        assertThatThrownBy(() -> codec.decode(wire.replace("\"sequence\":1", "\"sequence\":0"), TraceEvent.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> codec.decode(wire.replace("\"name\":", "\"source\":\"verifier_observed\",\"name\":"), TraceEvent.class)).isInstanceOf(Exception.class);
    }

    @Test void rejectsMissingAndNullPrimitiveCaptureFlags() throws Exception {
        String wire=codec.encode(validEvent());
        assertThatThrownBy(() -> codec.decode(wire.replace("\"redacted\":false,", ""),TraceEvent.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> codec.decode(wire.replace("\"redacted\":false", "\"redacted\":null"),TraceEvent.class)).isInstanceOf(Exception.class);
    }

    private TraceEvent validEvent() throws Exception {
        return new TraceEvent("1.0", UUID.fromString("00000000-0000-0000-0000-000000000002"), trialId,
            "11111111111111111111111111111111", "1111111111111111", null,
            EventType.SPAN_STARTED, SpanKind.TOOL, "query_order", 1,
            Instant.parse("2026-10-25T08:00:00Z"), codec.tree("{\"input\":{\"订单\":\"A\"}}"),
            new CaptureMetadata(false,false));
    }
}
