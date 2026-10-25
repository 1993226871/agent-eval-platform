package io.agenteval.workers;
import java.util.UUID;
/** Only returned at issuance. Never stored or included in list responses. */
public record IssuedCredential(UUID id,String secret) {}
