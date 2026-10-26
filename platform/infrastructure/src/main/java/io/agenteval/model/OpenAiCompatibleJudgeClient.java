package io.agenteval.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.agenteval.http.LimitedBodySubscriber;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One non-streaming request per send; all post-send transport errors are uncertain. */
public final class OpenAiCompatibleJudgeClient implements JudgeProvider,AutoCloseable {
    private final EncryptedModelProfileRepository profiles;
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public OpenAiCompatibleJudgeClient(EncryptedModelProfileRepository profiles) { this.profiles=profiles; }
    public PreparedCall prepare(ModelDispatch dispatch) {
        var profile=dispatch.profile(); profiles.checkEndpoint(profile);
        if(!profiles.require(profile.id()).enabled()) throw new ModelUnavailableException();
        String secret=profiles.credential(profile.id()); var input=dispatch.request();
        try {
            String body=json.writeValueAsString(Map.of("model",profile.model(),"messages",List.of(Map.of("role","system","content",input.systemPrompt()),Map.of("role","user","content",input.userPrompt())),
                "max_completion_tokens",input.maxOutputTokens(),"n",1,"stream",false,"response_format",Map.of("type","json_object"),"temperature",0));
            if(body.getBytes(StandardCharsets.UTF_8).length>profile.maxRequestBytes()) throw new IllegalArgumentException("Model request limit");
            var request=HttpRequest.newBuilder(URI.create(profile.endpoint())).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+secret)
                .header("Content-Type","application/json").header("Idempotency-Key",input.callId().toString()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return () -> send(request,secret);
        } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid provider request"); }
    }
    private ProviderResponse send(HttpRequest request,String secret) {
        var future=http.sendAsync(request,info -> new LimitedBodySubscriber(1024*1024));
        HttpResponse<byte[]> response;
        try { response=future.get(60,TimeUnit.SECONDS); }
        catch(Exception failure) { future.cancel(true); if(failure instanceof InterruptedException) Thread.currentThread().interrupt(); throw new ModelUnavailableException(); }
        if(response.statusCode()!=200) throw new ModelUnavailableException();
        try {
            JsonNode root=json.readTree(response.body());
            if(!root.isObject()) throw new IllegalArgumentException();
            redact(root,secret,0);
            String raw=json.writeValueAsString(root).replace(secret,"[REDACTED]");
            var usage=root.path("usage"); Long input=count(usage.path("prompt_tokens")),output=count(usage.path("completion_tokens")),total=count(usage.path("total_tokens"));
            if(input==null || output==null || total==null || !java.math.BigInteger.valueOf(input).add(java.math.BigInteger.valueOf(output)).equals(java.math.BigInteger.valueOf(total))) { input=null; output=null; }
            String content=text(root.path("choices").path(0).path("message").path("content"),65536);
            return new ProviderResponse(content,input,output,text(root.path("model"),200),text(root.path("choices").path(0).path("finish_reason"),100),text(root.path("id"),200),raw);
        } catch(Exception invalid) {
            // Invalid envelopes can hide encoded credential echoes; preserve a digest instead of unsafe raw text.
            return new ProviderResponse(null,null,null,null,null,null,"{\"capture\":\"INVALID_PROVIDER_JSON\",\"sha256\":\""+digest(response.body())+"\"}");
        }
    }
    private Long count(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>=0?value.longValue():null; }
    private String digest(byte[] value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private String text(JsonNode value,int limit) { return value.isTextual() && value.asText().length()<=limit?value.asText():null; }
    private void redact(JsonNode node,String secret,int depth) {
        if(depth>128) throw new IllegalArgumentException("Provider response nesting limit");
        if(node instanceof ObjectNode object) {
            var names=new ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for(String name:names) { var value=object.get(name); if(value.isTextual()) object.put(name,value.asText().replace(secret,"[REDACTED]")); else redact(value,secret,depth+1); }
        } else if(node instanceof ArrayNode array) {
            for(int index=0;index<array.size();index++) { var value=array.get(index); if(value.isTextual()) array.set(index,TextNode.valueOf(value.asText().replace(secret,"[REDACTED]"))); else redact(value,secret,depth+1); }
        }
    }
    @Override public void close() { http.shutdownNow(); }
}
