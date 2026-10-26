package io.agenteval.reporting;
import io.agenteval.evaluation.EvaluationAggregator;
import org.springframework.context.annotation.*;
@Configuration
public class ReportConfiguration {
    @Bean ReportService reportService(ReportRepository repository) { return new ReportService(repository,new EvaluationAggregator()); }
    @Bean ComparisonService comparisonService(ComparisonQueries queries) { return new ComparisonService(queries,new EvaluationAggregator()); }
    @Bean ReportExporter reportExporter(ComparisonQueries queries) { return new ReportExporter(queries,new EvaluationAggregator()); }
}
