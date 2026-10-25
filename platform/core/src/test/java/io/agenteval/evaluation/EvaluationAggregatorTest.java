package io.agenteval.evaluation;
import io.agenteval.protocol.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class EvaluationAggregatorTest {
    @Test void unscoredZeroWeightCriterionStillMarksReportAsPartial() {
        var hard=UUID.randomUUID(); var optional=UUID.randomUUID();
        var entries=List.of(new EvaluationEntry(hard,"outcome",true,BigDecimal.ONE),new EvaluationEntry(optional,"outcome",false,BigDecimal.ZERO));
        var result=new CriterionResult("hard",CriterionStatus.SCORED,BigDecimal.ONE,true,"Observed",List.of("output"),hard);
        var aggregate=new EvaluationAggregator().aggregate(entries,Map.of(hard,result),false);
        assertThat(aggregate.verdict()).isEqualTo("PASS");
        assertThat(aggregate.dimensions().get("outcome").coverage()).isEqualByComparingTo("1");
        assertThat(aggregate.dimensions().get("outcome").partial()).isTrue();
        assertThat(aggregate.dimensions().get("outcome").scoredCriteria()).isEqualTo(1);
    }
}
