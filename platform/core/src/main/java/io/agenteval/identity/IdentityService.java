package io.agenteval.identity;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Locale;
import java.util.UUID;

public final class IdentityService {
    private final IdentityRepository repository;
    private final PasswordEncoder passwords;
    public IdentityService(IdentityRepository repository,PasswordEncoder passwords) { this.repository=repository; this.passwords=passwords; }
    public UserAccount createUser(String email,String temporaryPassword,boolean admin) {
        String normalized=normalizeEmail(email); validatePassword(temporaryPassword);
        return repository.create(normalized,passwords.encode(temporaryPassword),admin);
    }
    public UserAccount requireAccount(UUID id) { return repository.findById(id).orElseThrow(ScopeViolationException::new); }
    public UserAccount loadForLogin(String email) {
        try { return repository.findByEmail(normalizeEmail(email)).orElseThrow(ScopeViolationException::new); }
        catch(IllegalArgumentException invalid) { throw new ScopeViolationException(); }
    }
    public void changePassword(WorkspaceActor actor,String current,String next) {
        validatePassword(next); var account=requireAccount(actor.userId()); WorkspaceScope.require(actor,account.workspaceId());
        if(current==null || !passwords.matches(current,account.passwordHash())) throw new InvalidPasswordException();
        if(current.equals(next)) throw new IllegalArgumentException("New password must differ");
        if(!repository.changePassword(account.userId(),account.passwordHash(),passwords.encode(next))) throw new InvalidPasswordException();
    }
    private static String normalizeEmail(String email) {
        if(email==null) throw new IllegalArgumentException("Email required");
        String value=email.strip().toLowerCase(Locale.ROOT);
        if(value.length()>320 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Invalid email");
        return value;
    }
    private static void validatePassword(String password) {
        if(password==null || password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("Password must be at least 12 characters and at most 72 UTF-8 bytes");
    }
    public static final class InvalidPasswordException extends RuntimeException {
        public InvalidPasswordException() { super("Password verification failed"); }
    }
}
