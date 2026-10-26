package io.agenteval.identity;
import java.util.Optional;
import java.util.UUID;
public interface IdentityRepository {
    UserAccount create(String email,String passwordHash,boolean admin);
    Optional<UserAccount> findByEmail(String email);
    Optional<UserAccount> findById(UUID userId);
    boolean changePassword(UUID userId,String previousHash,String nextHash);
    java.util.List<AccountSummary> list(int offset,int limit);
}
