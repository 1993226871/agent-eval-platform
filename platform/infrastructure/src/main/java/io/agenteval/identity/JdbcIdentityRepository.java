package io.agenteval.identity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdentityRepository implements IdentityRepository {
    @Override public java.util.List<AccountSummary> list(int offset,int limit) {
        return jdbc.sql("select u.id user_id,w.id workspace_id,u.email,u.admin,u.must_change_password from users u join workspaces w on w.owner_user_id=u.id order by u.created_at,u.id limit ? offset ?").params(limit,offset)
            .query((row,index)->new AccountSummary(row.getObject("user_id",UUID.class),row.getObject("workspace_id",UUID.class),row.getString("email"),row.getBoolean("admin"),row.getBoolean("must_change_password"))).list();
    }
    private static final String SELECT="select u.id user_id,w.id workspace_id,u.email,u.password_hash,u.must_change_password,u.admin from users u join workspaces w on w.owner_user_id=u.id ";
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    public JdbcIdentityRepository(JdbcClient jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; transactions=new TransactionTemplate(manager); }
    @Override public UserAccount create(String email,String passwordHash,boolean admin) {
        return transactions.execute(status -> {
            UUID userId=UUID.randomUUID(),workspaceId=UUID.randomUUID();
            jdbc.sql("insert into users(id,email,password_hash,must_change_password,admin) values (?,?,?,true,?)").params(userId,email,passwordHash,admin).update();
            jdbc.sql("insert into workspaces(id,owner_user_id) values (?,?)").params(workspaceId,userId).update();
            io.agenteval.administration.JdbcAuditWriter.system(jdbc,workspaceId,io.agenteval.administration.AuditAction.ACCOUNT_CREATED,userId);
            return new UserAccount(userId,workspaceId,email,passwordHash,true,admin);
        });
    }
    @Override public Optional<UserAccount> findByEmail(String email) { return jdbc.sql(SELECT+"where u.email=?").param(email).query(this::map).optional(); }
    @Override public Optional<UserAccount> findById(UUID id) { return jdbc.sql(SELECT+"where u.id=?").param(id).query(this::map).optional(); }
    @Override public boolean changePassword(UUID id,String previous,String next) {
        return transactions.execute(status -> {
            int changed=jdbc.sql("update users set password_hash=?,must_change_password=false where id=? and password_hash=?").params(next,id,previous).update();
            if(changed==1) jdbc.sql("delete from spring_session where principal_name=?").param(id.toString()).update();
            return changed==1;
        });
    }
    private UserAccount map(java.sql.ResultSet row,int index) throws java.sql.SQLException {
        return new UserAccount(row.getObject("user_id",UUID.class),row.getObject("workspace_id",UUID.class),row.getString("email"),row.getString("password_hash"),row.getBoolean("must_change_password"),row.getBoolean("admin"));
    }
}
