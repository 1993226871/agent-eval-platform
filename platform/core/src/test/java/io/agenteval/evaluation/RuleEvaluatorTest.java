package io.agenteval.evaluation;
import io.agenteval.protocol.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class RuleEvaluatorTest {
    final ProtocolCodec codec=new ProtocolCodec();
    final UUID trial=UUID.randomUUID(),snapshot=UUID.randomUUID();
    final RuleEvaluator rules=new RuleEvaluator();
    EvaluatorDefinition definition(String config) throws Exception { return new EvaluatorDefinition(UUID.randomUUID(),"amount","RULE",List.of(),new BigDecimal("0.75"),codec.tree(config)); }
    EvaluationContext context(String output,EvidenceCompleteness completeness,TraceEvent... events) throws Exception { return new EvaluationContext(trial,snapshot,codec.tree("{\"order_id\":\"A\"}"),codec.tree("{\"amount\":100}"),codec.tree(output),List.of(events),List.of(),completeness); }
    TraceEvent event(String span,String parent,EventType type,String name,long sequence,String payload) throws Exception { return new TraceEvent("1.0",UUID.randomUUID(),trial,"1".repeat(32),span,parent,type,SpanKind.TOOL,name,sequence,Instant.parse("2026-10-26T00:00:00Z").plusSeconds(sequence),codec.tree(payload),new CaptureMetadata(false,false)); }
    @Test void distinguishesMissingEvidenceFromFailure() throws Exception {
        var definition=definition("{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected_selector\":\"/expected/amount\"}");
        var correct=rules.evaluate(definition,context("{\"amount\":100}",EvidenceCompleteness.NONE));
        assertThat(correct.status()).isEqualTo(CriterionStatus.SCORED); assertThat(correct.passed()).isTrue(); assertThat(correct.score()).isEqualByComparingTo("1");
        var wrong=rules.evaluate(definition,context("{\"amount\":80}",EvidenceCompleteness.COMPLETE)); assertThat(wrong.passed()).isFalse(); assertThat(wrong.score()).isEqualByComparingTo("0");
        var missing=rules.evaluate(definition,context("{}",EvidenceCompleteness.PARTIAL)); assertThat(missing.status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE); assertThat(missing.score()).isNull(); assertThat(missing.passed()).isNull();
        var absentTool=rules.evaluate(definition("{\"operator\":\"tool_arguments\",\"tool\":\"refund\",\"selector\":\"/amount\",\"comparison\":\"eq\",\"expected\":100,\"aggregation\":\"all\"}"),context("{}",EvidenceCompleteness.PARTIAL));
        assertThat(absentTool.status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
    }
    @Test void allowsExplicitAbsenceOnlyWhenEvidenceCanEstablishIt() throws Exception {
        var definition=definition("{\"operator\":\"tool_set\",\"tools\":[\"delete_order\"],\"mode\":\"absent\"}");
        assertThat(rules.evaluate(definition,context("{}",EvidenceCompleteness.PARTIAL)).status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(rules.evaluate(definition,context("{}",EvidenceCompleteness.COMPLETE)).passed()).isTrue();
        var forbidden=event("2".repeat(16),null,EventType.SPAN_STARTED,"delete_order",1,"{\"input\":{}}");
        assertThat(rules.evaluate(definition,context("{}",EvidenceCompleteness.COMPLETE,forbidden)).passed()).isFalse();
        var missing=definition("{\"operator\":\"exists\",\"selector\":\"/output/not_present\",\"expected\":false}");
        assertThat(rules.evaluate(missing,context("{}",EvidenceCompleteness.COMPLETE)).passed()).isTrue();
        assertThat(rules.evaluate(missing,context("{}",EvidenceCompleteness.PARTIAL)).status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
    }
    @Test void aggregatesRetriedToolsExplicitly() throws Exception {
        var first=event("2".repeat(16),null,EventType.SPAN_STARTED,"refund",1,"{\"input\":{\"amount\":100}}");
        var retry=event("3".repeat(16),null,EventType.SPAN_STARTED,"refund",2,"{\"input\":{\"amount\":80}}");
        var context=context("{}",EvidenceCompleteness.COMPLETE,first,retry);
        String base="{\"operator\":\"tool_arguments\",\"tool\":\"refund\",\"selector\":\"/amount\",\"comparison\":\"eq\",\"expected\":100";
        assertThat(rules.evaluate(definition(base+",\"aggregation\":\"any\"}"),context).passed()).isTrue();
        assertThat(rules.evaluate(definition(base+",\"aggregation\":\"all\"}"),context).passed()).isFalse();
        assertThat(rules.evaluate(definition(base+",\"aggregation\":\"count\",\"match_count\":1}"),context).passed()).isTrue();
        assertThat(rules.evaluate(definition(base+",\"aggregation\":\"count\",\"match_count\":1.0}"),context).passed()).isTrue();
        assertThat(rules.evaluate(definition(base+"}"),context).status()).isEqualTo(CriterionStatus.ERROR);
    }
    @Test void checksCausalityInsteadOfArrivalOrder() throws Exception {
        var lookup=event("2".repeat(16),null,EventType.SPAN_STARTED,"lookup",1,"{\"input\":{}}");
        var done=event("2".repeat(16),null,EventType.SPAN_COMPLETED,"lookup",2,"{\"duration_ms\":5}");
        var refund=event("3".repeat(16),null,EventType.SPAN_STARTED,"refund",3,"{\"input\":{}}");
        var definition=definition("{\"operator\":\"causal_before\",\"before\":\"lookup\",\"after\":\"refund\",\"relation\":\"dependency\",\"aggregation\":\"all\"}");
        assertThat(rules.evaluate(definition,context("{}",EvidenceCompleteness.COMPLETE,refund,done,lookup)).status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
        var linked=event("3".repeat(16),null,EventType.SPAN_STARTED,"refund",3,"{\"input\":{\"depends_on_span_id\":\""+lookup.spanId()+"\"}}");
        assertThat(rules.evaluate(definition,context("{}",EvidenceCompleteness.COMPLETE,linked,done,lookup)).passed()).isTrue();
        var reversed=event("2".repeat(16),null,EventType.SPAN_STARTED,"lookup",3,"{\"input\":{\"depends_on_span_id\":\""+refund.spanId()+"\"}}");
        var refundFirst=event(refund.spanId(),null,EventType.SPAN_STARTED,"refund",1,"{\"input\":{}}");
        var refundDone=event(refund.spanId(),null,EventType.SPAN_COMPLETED,"refund",2,"{\"duration_ms\":5}");
        var lookupDone=event(lookup.spanId(),null,EventType.SPAN_COMPLETED,"lookup",4,"{\"duration_ms\":5}");
        assertThat(rules.evaluate(definition,context("{}",EvidenceCompleteness.COMPLETE,reversed,lookupDone,refundFirst,refundDone)).passed()).isFalse();
    }
    @Test void usesLocalDurationsAndCompleteCounts() throws Exception {
        var start=event("2".repeat(16),null,EventType.SPAN_STARTED,"lookup",1,"{\"input\":{}}");
        var end=event("2".repeat(16),null,EventType.SPAN_COMPLETED,"lookup",900,"{\"duration_ms\":5}");
        var duration=definition("{\"operator\":\"duration_ms\",\"tool\":\"lookup\",\"comparison\":\"lte\",\"expected\":6,\"aggregation\":\"sum\"}");
        assertThat(rules.evaluate(duration,context("{}",EvidenceCompleteness.COMPLETE,end,start)).passed()).isTrue();
        var count=definition("{\"operator\":\"count\",\"tool\":\"lookup\",\"comparison\":\"eq\",\"expected\":1}");
        assertThat(rules.evaluate(count,context("{}",EvidenceCompleteness.COMPLETE,start,end)).passed()).isTrue();
        assertThat(rules.evaluate(count,context("{}",EvidenceCompleteness.PARTIAL,start,end)).status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
    }
    @Test void validatesBoundedSchemaWithoutRemoteReferencesOrExpressions() throws Exception {
        var schema=definition("{\"operator\":\"schema\",\"selector\":\"/output\",\"schema\":{\"type\":\"object\",\"required\":[\"amount\"],\"properties\":{\"amount\":{\"type\":\"integer\",\"minimum\":0}},\"additionalProperties\":false}}");
        assertThat(rules.evaluate(schema,context("{\"amount\":100}",EvidenceCompleteness.NONE)).passed()).isTrue();
        assertThat(rules.evaluate(schema,context("{\"amount\":\"wrong\"}",EvidenceCompleteness.NONE)).passed()).isFalse();
        var remote=definition("{\"operator\":\"schema\",\"selector\":\"/output\",\"schema\":{\"$ref\":\"https://attacker.invalid/schema\"}}");
        assertThat(rules.evaluate(remote,context("{}",EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
        assertThat(rules.evaluate(definition("{\"operator\":\"eval\",\"expression\":\"runtime.exec()\"}"),context("{}",EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
        assertThat(rules.evaluate(definition("{\"operator\":\"eq\",\"selector\":\"/secrets/key\",\"expected\":1}"),context("{}",EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
    }
    @Test void rejectsUnboundedNumbersAndUnknownEvidenceReferences() throws Exception {
        var numeric=definition("{\"operator\":\"gte\",\"selector\":\"/output/amount\",\"expected\":1e1000000}");
        assertThat(rules.evaluate(numeric,context("{\"amount\":100}",EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
        var context=context("{}",EvidenceCompleteness.COMPLETE);
        assertThatThrownBy(() -> new EvidenceReferenceValidator().validate(context,List.of("event:"+UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new EvidenceReferenceValidator().validate(context,List.of("output","snapshot:"+snapshot))).doesNotThrowAnyException();
    }
    @Test void sharesOrderOutcomeFixturesAndProducesValidTypedResults() throws Exception {
        var directory=java.nio.file.Path.of(System.getProperty("contracts.dir"),"fixtures/v1/evaluation");
        for(String name:List.of("order-pass","order-fail","order-insufficient")) {
            var fixture=codec.tree(java.nio.file.Files.readString(directory.resolve(name+".json")));
            var definition=definition(fixture.get("config").toString());
            var result=rules.evaluate(definition,context(fixture.get("output").toString(),EvidenceCompleteness.valueOf(fixture.path("completeness").asText())));
            assertThat(result.status().name()).isEqualTo(fixture.path("status").asText());
            if(fixture.get("score").isNull()) { assertThat(result.score()).isNull(); assertThat(result.passed()).isNull(); }
            else { assertThat(result.score()).isEqualByComparingTo(fixture.get("score").decimalValue()); assertThat(result.passed()).isEqualTo(fixture.get("passed").asBoolean()); }
            assertThat(codec.decode(codec.encode(result),CriterionResult.class)).isEqualTo(result);
        }
    }
    @Test void invalidConfigurationDoesNotHideBehindMissingEvidence() throws Exception {
        var config=definition("{\"operator\":\"tool_arguments\",\"tool\":\"refund\",\"selector\":\"/amount\",\"comparison\":\"eval\",\"expected\":100,\"aggregation\":\"any\"}");
        assertThat(rules.evaluate(config,context("{}",EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
        var root=definition("{\"operator\":\"eq\",\"selector\":\"/output/missing\",\"expected_selector\":\"/secrets/key\"}");
        assertThat(rules.evaluate(root,context("{}",EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
    }
    @Test void boundedSchemaAndSelectorsRejectComplexityBeforeEvaluation() throws Exception {
        var schema=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(); var current=schema;
        for(int i=0;i<10;i++) { current.put("type","array"); current=current.putObject("items"); }
        var config=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("operator","schema").put("selector","/output"); config.set("schema",schema);
        assertThat(rules.evaluate(definition(config.toString()),context("[]",EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
        var pointer=definition("{\"operator\":\"exists\",\"selector\":\"/output"+"/a".repeat(33)+"\",\"expected\":false}");
        assertThat(rules.evaluate(pointer,context("{}",EvidenceCompleteness.COMPLETE)).status()).isEqualTo(CriterionStatus.ERROR);
    }
    @Test void partialEvidenceSupportsObservedViolationsButDoesNotProveAllRetriesCorrect() throws Exception {
        var correct=event("2".repeat(16),null,EventType.SPAN_STARTED,"refund",1,"{\"input\":{\"amount\":100}}");
        var wrong=event("3".repeat(16),null,EventType.SPAN_STARTED,"refund",2,"{\"input\":{\"amount\":80}}");
        var config=definition("{\"operator\":\"tool_arguments\",\"tool\":\"refund\",\"selector\":\"/amount\",\"comparison\":\"eq\",\"expected\":100,\"aggregation\":\"all\"}");
        assertThat(rules.evaluate(config,context("{}",EvidenceCompleteness.PARTIAL,correct)).status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(rules.evaluate(config,context("{}",EvidenceCompleteness.PARTIAL,wrong)).passed()).isFalse();
    }
    @Test void exactToolSetRequiresCompleteEvidenceButObservedExtraToolsProveFailure() throws Exception {
        var lookup=event("2".repeat(16),null,EventType.SPAN_STARTED,"lookup",1,"{\"input\":{}}");
        var extra=event("3".repeat(16),null,EventType.SPAN_STARTED,"delete",2,"{\"input\":{}}");
        var exact=definition("{\"operator\":\"tool_set\",\"tools\":[\"lookup\"],\"mode\":\"exact\"}");
        assertThat(rules.evaluate(exact,context("{}",EvidenceCompleteness.PARTIAL,lookup)).status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(rules.evaluate(exact,context("{}",EvidenceCompleteness.COMPLETE,lookup)).passed()).isTrue();
        assertThat(rules.evaluate(exact,context("{}",EvidenceCompleteness.PARTIAL,lookup,extra)).passed()).isFalse();
        var contains=definition("{\"operator\":\"tool_set\",\"tools\":[\"lookup\"],\"mode\":\"contains_all\"}");
        assertThat(rules.evaluate(contains,context("{}",EvidenceCompleteness.PARTIAL,lookup)).passed()).isTrue();
    }
    @Test void limitsSchemaEvaluationWork() throws Exception {
        var schema=definition("{\"operator\":\"schema\",\"selector\":\"/output\",\"schema\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}}");
        String large="["+String.join(",",Collections.nCopies(5000,"1"))+"]";
        assertThat(rules.evaluate(schema,context(large,EvidenceCompleteness.NONE)).status()).isEqualTo(CriterionStatus.ERROR);
    }
    @Test void invalidCountTargetIsConfigurationError() throws Exception {
        var count=definition("{\"operator\":\"count\",\"tool\":\"refund\",\"comparison\":\"eq\",\"expected\":\"one\"}");
        assertThat(rules.evaluate(count,context("{}",EvidenceCompleteness.COMPLETE)).status()).isEqualTo(CriterionStatus.ERROR);
    }
    @Test void jsonNumericEqualityIsConsistentInsideObjects() throws Exception {
        var config=definition("{\"operator\":\"eq\",\"selector\":\"/output\",\"expected\":{\"amount\":100.0}}");
        assertThat(rules.evaluate(config,context("{\"amount\":100}",EvidenceCompleteness.NONE)).passed()).isTrue();
        var schema=definition("{\"operator\":\"schema\",\"selector\":\"/output\",\"schema\":{\"const\":{\"amount\":100.0}}}");
        assertThat(rules.evaluate(schema,context("{\"amount\":100}",EvidenceCompleteness.NONE)).passed()).isTrue();
    }
    @Test void contradictoryDependencyLinkDoesNotProveAnOrder() throws Exception {
        var lookup=event("2".repeat(16),null,EventType.SPAN_STARTED,"lookup",1,"{\"input\":{}}");
        var premature=event("3".repeat(16),null,EventType.SPAN_STARTED,"refund",2,"{\"input\":{\"depends_on_span_id\":\""+lookup.spanId()+"\"}}");
        var futureEnd=event(lookup.spanId(),null,EventType.SPAN_COMPLETED,"lookup",3,"{\"duration_ms\":5}");
        var rule=definition("{\"operator\":\"causal_before\",\"before\":\"lookup\",\"after\":\"refund\",\"relation\":\"dependency\",\"aggregation\":\"all\"}");
        assertThat(rules.evaluate(rule,context("{}",EvidenceCompleteness.COMPLETE,lookup,premature,futureEnd)).status()).isEqualTo(CriterionStatus.INSUFFICIENT_EVIDENCE);
    }
}
