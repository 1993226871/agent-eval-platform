package io.agenteval.worker;

import io.agenteval.experiments.ExperimentDeletionService;
import org.slf4j.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public final class CleanupJobRunner {
    private static final Logger log=LoggerFactory.getLogger(CleanupJobRunner.class);
    private final ExperimentDeletionService deletion;
    public CleanupJobRunner(ExperimentDeletionService deletion) { this.deletion=deletion; }
    @Scheduled(fixedDelay=5000,scheduler="cleanupScheduler") public void run() {
        try { deletion.cleanupReady(1); }
        catch(RuntimeException failure) { log.warn("Experiment cleanup could not finish; durable retry remains queued ({})",failure.getClass().getSimpleName()); }
    }
    @Configuration static class Schedulers {
        @Bean(name="cleanupScheduler") ThreadPoolTaskScheduler cleanupScheduler() { return scheduler(1,"artifact-cleanup-"); }
        @Bean(name="taskScheduler") ThreadPoolTaskScheduler taskScheduler() { return scheduler(4,"platform-jobs-"); }
        private ThreadPoolTaskScheduler scheduler(int threads,String prefix) {
            var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(threads); scheduler.setThreadNamePrefix(prefix); scheduler.setWaitForTasksToCompleteOnShutdown(false); scheduler.setAwaitTerminationSeconds(5); return scheduler;
        }
    }
}
