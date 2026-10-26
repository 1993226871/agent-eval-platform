package io.agenteval.quota;

import io.agenteval.identity.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class QuotaConcurrencyIT {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static JdbcTemplate sql;
    static DataSourceTransactionManager tx;
    static UUID legacyWorkspace;
    QuotaService quota;
    WorkspaceActor user, admin;
    @BeforeAll static void database() {
        postgres=new PostgreSQLContainer<>("postgres:17.11"); postgres.start();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).target("7").load().migrate();
        jdbc=JdbcClient.create(ds); sql=new JdbcTemplate(ds); tx=new DataSourceTransactionManager(ds);
        // Seed the historical schema directly: current user provisioning also requires newer audit tables.
        UUID legacyUser=UUID.randomUUID(); legacyWorkspace=UUID.randomUUID();
        jdbc.sql("insert into users(id,email,password_hash,must_change_password,admin) values(?,?,?,true,false)").params(legacyUser,"legacy-"+legacyUser+"@quota.local","unused-legacy-fixture-hash").update();
        jdbc.sql("insert into workspaces(id,owner_user_id) values(?,?)").params(legacyWorkspace,legacyUser).update();
        Flyway.configure().dataSource(ds).load().migrate();
    }
    @AfterAll static void stop() { if(postgres!=null) postgres.stop(); }
    static WorkspaceActor create(boolean admin) {
        return new IdentityService(new JdbcIdentityRepository(jdbc,tx),new BCryptPasswordEncoder(4))
            .createUser(UUID.randomUUID()+"@quota.local","Temporary-Password-1",admin).actor();
    }
    @BeforeEach void setup() { quota=new QuotaService(new JdbcQuotaRepository(jdbc,tx)); user=create(false); admin=create(true); }

    @Test void neverOverspendsAndSettlesExactlyOnce() throws Exception {
        assertThat(quota.wallet(user).available()).isZero();
        assertThat(jdbc.sql("select count(*) from quota_wallets where workspace_id=? and granted=0").param(legacyWorkspace).query(Long.class).single()).isEqualTo(1);
        quota.grant(admin,user.workspaceId(),1000,"Initial cumulative credits",UUID.randomUUID());
        var ready=new CountDownLatch(20); var start=new CountDownLatch(1); var futures=new ArrayList<Future<Boolean>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int index=0;index<20;index++) futures.add(pool.submit(() -> {
                ready.countDown(); start.await();
                try { quota.reserve(user.workspaceId(),UUID.randomUUID(),100); return true; }
                catch(InsufficientQuotaException expected) { return false; }
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            int successes=0; for(var future:futures) if(future.get(20,TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(10);
        }
        assertThat(quota.wallet(user).reserved()).isEqualTo(1000);
        assertThat(quota.wallet(user).available()).isZero();
        var second=create(false); var call=UUID.randomUUID();
        quota.grant(admin,second.workspaceId(),100,"Second account",UUID.randomUUID());
        quota.reserve(second.workspaceId(),call,100); quota.settle(call,60); quota.settle(call,60);
        assertThat(quota.wallet(second).available()).isEqualTo(40);
        assertThat(quota.wallet(second).consumed()).isEqualTo(60);
        assertThat(quota.wallet(second).reserved()).isZero();
        assertThat(quota.entries(second,100).stream().filter(entry -> entry.type().equals("SETTLE")).count()).isEqualTo(1);
        assertThatThrownBy(() -> quota.settle(call,50)).isInstanceOf(QuotaConflictException.class);
        assertThatThrownBy(() -> quota.reserve(second.workspaceId(),call,90)).isInstanceOf(QuotaConflictException.class);
        assertThatThrownBy(() -> quota.grant(admin,second.workspaceId(),-41,"Too low",UUID.randomUUID())).isInstanceOf(InsufficientQuotaException.class);
        assertThatThrownBy(() -> quota.grant(user,user.workspaceId(),10,"Self grant",UUID.randomUUID())).isInstanceOf(ScopeViolationException.class);
    }
    @Test void pagesFullLedgerWithWorkspaceBoundCursors() {
        for(int i=0;i<3;i++) quota.grant(admin,user.workspaceId(),10,"Budget "+i,UUID.randomUUID());
        var first=quota.page(user,null,2); assertThat(first.items()).hasSize(2); assertThat(first.nextCursor()).isNotNull();
        var second=quota.page(user,first.nextCursor(),2); assertThat(second.items()).hasSize(1); assertThat(second.nextCursor()).isNull();
        assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
        assertThatThrownBy(() -> quota.page(create(false),first.nextCursor(),2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void uncertainSendCannotReleaseAndAdjustmentsAreIdempotent() {
        var key=UUID.randomUUID(); var call=UUID.randomUUID();
        quota.grant(admin,user.workspaceId(),100,"Grant",key); quota.grant(admin,user.workspaceId(),100,"Grant",key);
        assertThatThrownBy(() -> quota.grant(admin,user.workspaceId(),101,"Grant",key)).isInstanceOf(QuotaConflictException.class);
        quota.reserve(user.workspaceId(),call,80); quota.markSendIntent(call); quota.markReconciliation(call);
        assertThatThrownBy(() -> quota.releaseUnsent(call)).isInstanceOf(QuotaConflictException.class);
        assertThat(quota.wallet(user).reserved()).isEqualTo(80);
        assertThatThrownBy(() -> quota.settle(call,81)).isInstanceOf(QuotaConflictException.class);
        assertThat(quota.wallet(user).reserved()).isEqualTo(80);
        quota.settle(call,50);
        var unsent=UUID.randomUUID(); quota.reserve(user.workspaceId(),unsent,30); quota.releaseUnsent(unsent); quota.releaseUnsent(unsent);
        assertThat(quota.wallet(user).available()).isEqualTo(50);
        var entries=quota.entries(user,100);
        assertThat(entries.stream().mapToLong(QuotaEntry::grantedDelta).sum()).isEqualTo(100);
        assertThat(entries.stream().mapToLong(QuotaEntry::consumedDelta).sum()).isEqualTo(50);
        assertThat(entries.stream().mapToLong(QuotaEntry::reservedDelta).sum()).isZero();
        assertThatThrownBy(() -> quota.reserve(create(false).workspaceId(),call,80)).isInstanceOf(QuotaConflictException.class);
        assertThat(quota.entries(create(false),100)).isEmpty();
    }

    @Test void ledgerFailureRollsBackWalletAndOverflowIsRejected() {
        quota.grant(admin,user.workspaceId(),Long.MAX_VALUE,"Maximum",UUID.randomUUID());
        assertThatThrownBy(() -> quota.grant(admin,user.workspaceId(),1,"Overflow",UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        sql.execute("create function quota_test_failure() returns trigger language plpgsql as $$ begin if NEW.entry_type='RESERVE' then raise exception 'injected failure'; end if; return NEW; end $$");
        sql.execute("create trigger quota_test_failure before insert on quota_ledger for each row execute function quota_test_failure()");
        try {
            assertThatThrownBy(() -> quota.reserve(user.workspaceId(),UUID.randomUUID(),100)).isInstanceOf(RuntimeException.class);
            assertThat(quota.wallet(user).reserved()).isZero();
            assertThat(quota.wallet(user).available()).isEqualTo(Long.MAX_VALUE);
        } finally { sql.execute("drop trigger quota_test_failure on quota_ledger"); sql.execute("drop function quota_test_failure()"); }
        assertThatThrownBy(() -> sql.update("update quota_ledger set reason='mutated' where workspace_id=?",user.workspaceId())).isInstanceOf(RuntimeException.class);
    }
}
