package io.agenteval.worker;
import io.agenteval.evaluation.EvaluationOrchestrator;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public final class EvaluationJobRunner {
    private final EvaluationOrchestrator evaluations;
    public EvaluationJobRunner(EvaluationOrchestrator evaluations) { this.evaluations=evaluations; }
    @Scheduled(fixedDelayString="${app.evaluation.poll-delay-ms:250}") public void runReady() { drainOnce(); }
    public int drainOnce() { evaluations.schedule(200); int completed=0; while(completed<20 && evaluations.runNext()) completed++; return completed; }
}
