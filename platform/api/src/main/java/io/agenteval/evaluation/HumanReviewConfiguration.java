package io.agenteval.evaluation;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class HumanReviewConfiguration {
    @Bean HumanReviewService humanReviewService(HumanReviewRepository repository,Clock clock) { return new HumanReviewService(repository,clock); }
}
