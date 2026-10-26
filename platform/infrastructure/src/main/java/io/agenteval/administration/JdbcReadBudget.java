package io.agenteval.administration;

import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Size check and body read use the same database snapshot. */
public final class JdbcReadBudget {
    private final TransactionTemplate read;
    public JdbcReadBudget(PlatformTransactionManager manager) {
        read=new TransactionTemplate(manager); read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public <T> T load(Supplier<Long> bytes,Supplier<T> body) {
        return read.execute(status -> { if(bytes.get()>8L*1024*1024) throw new ResourceReadLimitException(); return body.get(); });
    }
}
