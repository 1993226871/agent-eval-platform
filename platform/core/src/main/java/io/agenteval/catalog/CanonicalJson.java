package io.agenteval.catalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;
public final class CanonicalJson {
    private CanonicalJson() {}
    public static String hash(JsonNode node) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sort(node).toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode sort(JsonNode node) {
        if(node.isObject()) {
            var result=JsonNodeFactory.instance.objectNode(); var fields=new TreeSet<String>(); node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(key -> result.set(key,sort(node.get(key)))); return result;
        }
        if(node.isArray()) { var result=JsonNodeFactory.instance.arrayNode(); node.forEach(item -> result.add(sort(item))); return result; }
        return node;
    }
}
