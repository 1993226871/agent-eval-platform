package io.agenteval.reporting;
import io.agenteval.evaluation.EvaluationAggregator;
import org.springframework.context.annotation.*;
@Configuration
public class ReportConfiguration {
    @Bean ReportService reportService(ReportRepository repository) { return new ReportService(repository,new EvaluationAggregator()); }
}
