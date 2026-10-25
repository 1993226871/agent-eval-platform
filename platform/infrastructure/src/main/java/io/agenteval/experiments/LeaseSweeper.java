package io.agenteval.experiments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.time.*;
import java.sql.Timestamp;

@Component
public final class LeaseSweeper {
    private final JdbcClient jdbc;
    private final Clock clock;
    public LeaseSweeper(JdbcClient jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    public int sweep(int limit) { return expire(jdbc,clock.instant(),limit); }
    static int expire(JdbcClient jdbc,Instant now,int limit) {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Invalid expiry batch size");
        return jdbc.sql("with expired as (select l.trial_id,l.execution_deadline from trial_leases l join trials t on t.id=l.trial_id where t.status in ('LEASED','RUNNING') and l.expires_at<=? order by l.expires_at,l.trial_id limit ? for update of t,l skip locked) update trials t set status=case when x.execution_deadline<=? then 'TIMED_OUT' else 'EXECUTION_UNKNOWN' end,completed_at=? from expired x where t.id=x.trial_id")
            .params(Timestamp.from(now),limit,Timestamp.from(now),Timestamp.from(now)).update();
    }
}
