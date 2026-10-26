package io.agenteval.identity;
import java.util.UUID;
public record AccountSummary(UUID userId,UUID workspaceId,String email,boolean admin,boolean mustChangePassword) {
    public static AccountSummary of(UserAccount account) { return new AccountSummary(account.userId(),account.workspaceId(),account.email(),account.admin(),account.mustChangePassword()); }
}
