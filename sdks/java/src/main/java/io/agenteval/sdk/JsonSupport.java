package io.agenteval.sdk;
import com.fasterxml.jackson.databind.JsonNode;
import io.agenteval.protocol.ProtocolCodec;
public final class JsonSupport {
    private static final ProtocolCodec CODEC=new ProtocolCodec();
    private JsonSupport() {}
    public static JsonNode tree(String json) { try { return CODEC.tree(json); } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid JSON"); } }
    public static JsonNode value(Object value) { return tree(encode(value)); }
    public static String encode(Object value) { try { return CODEC.encode(value); } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Value is not JSON-compatible"); } }
    public static <T> T decode(String value,Class<T> type) { try { return CODEC.decode(value,type); } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid protocol message"); } }
}
