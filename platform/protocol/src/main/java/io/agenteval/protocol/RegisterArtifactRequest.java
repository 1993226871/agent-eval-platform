package io.agenteval.protocol;
public record RegisterArtifactRequest(String name,String mediaType,long sizeBytes,String sha256,String idempotencyKey) {
    public RegisterArtifactRequest {
        if(name==null || name.isBlank() || name.length()>200 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid artifact name");
        if(mediaType==null || mediaType.length()>200 || !mediaType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) throw new IllegalArgumentException("Invalid media type");
        if(sizeBytes<0 || sizeBytes>104857600) throw new IllegalArgumentException("Invalid artifact size");
        if(sha256==null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        if(idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid idempotency key");
    }
}
