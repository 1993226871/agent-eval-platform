package io.agenteval.experiments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public final class QueuedTrialExpiry {
    private final JdbcClient jdbc;
    private final Clock clock;
    public QueuedTrialExpiry(JdbcClient jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    public int expire(int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Invalid expiry batch size");
        return jdbc.sql("with expired as (select t.id from trials t join experiments e on e.id=t.experiment_id where t.status='QUEUED' and e.deadline<=? order by t.created_at,t.id limit ? for update of t skip locked) update trials t set status='TIMED_OUT' from expired x where t.id=x.id")
            .params(java.sql.Timestamp.from(clock.instant()),limit).update();
    }
}
