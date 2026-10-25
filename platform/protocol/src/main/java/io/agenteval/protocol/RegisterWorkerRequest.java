package io.agenteval.protocol;
import java.util.*;
public record RegisterWorkerRequest(UUID agentId,String agentVersion,String instanceId,String protocolVersion,String sdkLanguage,String sdkVersion,int maxConcurrency) {
    public RegisterWorkerRequest {
        Objects.requireNonNull(agentId);
        if(!"1.0".equals(protocolVersion) || !Set.of("JAVA","PYTHON").contains(sdkLanguage) || maxConcurrency<1 || maxConcurrency>8
            || instanceId==null || !instanceId.matches("[a-zA-Z0-9_.:-]{1,128}") || agentVersion==null || agentVersion.isBlank() || agentVersion.length()>128
            || sdkVersion==null || sdkVersion.isBlank() || sdkVersion.length()>128) throw new IllegalArgumentException("Invalid worker registration");
    }
}
