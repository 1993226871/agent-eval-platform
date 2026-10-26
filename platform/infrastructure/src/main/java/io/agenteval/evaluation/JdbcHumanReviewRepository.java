package io.agenteval.evaluation;
import io.agenteval.identity.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcHumanReviewRepository implements HumanReviewRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcHumanReviewRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; transactions=new TransactionTemplate(manager); }
    private void require(WorkspaceActor actor,UUID result,boolean lock) {
        if(jdbc.sql("select job_id from evaluation_results where workspace_id=? and job_id=?"+(lock?" for key share":"")).params(actor.workspaceId(),result).query(UUID.class).optional().isEmpty()) throw new ScopeViolationException();
    }
    public HumanReview add(WorkspaceActor actor,UUID resultId,BigDecimal score,String reason,Instant now) {
        return transactions.execute(status -> {
            require(actor,resultId,true); UUID id=UUID.randomUUID();
            jdbc.sql("insert into human_reviews(id,workspace_id,result_id,score,reason,reviewer_id,created_at) values(?,?,?,?,?,?,?)").params(id,actor.workspaceId(),resultId,score,reason,actor.userId(),Timestamp.from(now)).update();
            return new HumanReview(id,resultId,score,reason,actor.userId(),now);
        });
    }
    public List<HumanReview> list(WorkspaceActor actor,UUID resultId,int limit) {
        return transactions.execute(status -> {
            require(actor,resultId,true);
            return jdbc.sql("select * from human_reviews where workspace_id=? and result_id=? order by created_at desc,id desc limit ?").params(actor.workspaceId(),resultId,limit)
                .query((row,index)->new HumanReview(row.getObject("id",UUID.class),resultId,row.getBigDecimal("score"),row.getString("reason"),row.getObject("reviewer_id",UUID.class),row.getTimestamp("created_at").toInstant())).list();
        });
    }
}
