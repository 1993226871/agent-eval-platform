package io.agenteval.workers;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class TokenSecrets {
    private static final SecureRandom RANDOM=new SecureRandom();
    private TokenSecrets() {}
    public static String generate() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String hash(String secret) {
        if(secret==null || secret.length()>512) throw new InvalidWorkerCredentialException();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static boolean matches(String expected,String secret) {
        if(secret==null || secret.length()>512) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hash(secret).getBytes(StandardCharsets.US_ASCII));
    }
}
