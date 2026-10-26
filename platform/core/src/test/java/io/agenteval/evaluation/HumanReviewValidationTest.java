package io.agenteval.evaluation;

import io.agenteval.identity.WorkspaceActor;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HumanReviewValidationTest {
    @Test void rejectsExtremeNegativeScaleEvenForZeroBeforeDatabaseConversion() {
        var repository=mock(HumanReviewRepository.class); var service=new HumanReviewService(repository,Clock.systemUTC());
        var actor=new WorkspaceActor(UUID.randomUUID(),UUID.randomUUID(),false);
        assertThatThrownBy(() -> service.add(actor,UUID.randomUUID(),new BigDecimal("0E+1000000000"),"Invalid exponent")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
