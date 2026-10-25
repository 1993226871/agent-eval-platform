package io.agenteval.evaluation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** An explicit JSON Schema subset. No refs, regexes, format plugins or executable extensions. */
final class BoundedJsonSchema {
    private static final Set<String> KEYS=Set.of("type","properties","required","additionalProperties","items","minItems","maxItems","minLength","maxLength","minimum","maximum","enum","const");
    private static final Set<String> TYPES=Set.of("object","array","string","integer","number","boolean","null");
    void check(JsonNode schema) { check(schema,0,new int[]{0}); }
    private void check(JsonNode schema,int depth,int[] count) {
        if(depth>8 || ++count[0]>256 || !schema.isObject()) throw new IllegalArgumentException("Schema complexity limit");
        schema.fieldNames().forEachRemaining(key -> { if(!KEYS.contains(key)) throw new IllegalArgumentException("Unsupported schema keyword"); });
        if(schema.has("type") && (!schema.get("type").isTextual() || !TYPES.contains(schema.get("type").asText()))) throw new IllegalArgumentException("Invalid schema type");
        if(schema.has("properties")) { var properties=schema.get("properties"); if(!properties.isObject() || properties.size()>64) throw new IllegalArgumentException("Invalid properties"); properties.forEach(child -> check(child,depth+1,count)); }
        if(schema.has("required")) { var required=schema.get("required"); if(!required.isArray() || required.size()>64) throw new IllegalArgumentException("Invalid required fields"); for(var key:required) if(!key.isTextual() || key.asText().length()>200) throw new IllegalArgumentException("Invalid required field"); }
        if(schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean()) throw new IllegalArgumentException("Invalid additionalProperties");
        if(schema.has("items")) check(schema.get("items"),depth+1,count);
        for(String key:List.of("minItems","maxItems","minLength","maxLength")) if(schema.has(key) && (!schema.get(key).isIntegralNumber() || !schema.get(key).canConvertToInt() || schema.get(key).asInt()<0 || schema.get(key).asInt()>1000000)) throw new IllegalArgumentException("Invalid schema bound");
        for(String key:List.of("minimum","maximum")) if(schema.has(key)) RuleEvaluator.number(schema.get(key));
        if(schema.has("enum") && (!schema.get("enum").isArray() || schema.get("enum").size()<1 || schema.get("enum").size()>64)) throw new IllegalArgumentException("Invalid enum");
        if(schema.toString().length()>65536) throw new IllegalArgumentException("Schema size limit");
    }
    boolean matches(JsonNode value,JsonNode schema) {
        return matches(value,schema,new int[]{0});
    }
    private boolean matches(JsonNode value,JsonNode schema,int[] work) {
        if(++work[0]>4096) throw new IllegalArgumentException("Schema evaluation budget exceeded");
        if(schema.has("type")) {
            boolean matches=switch(schema.get("type").asText()) { case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual(); case "integer" -> value.isNumber() && RuleEvaluator.number(value).stripTrailingZeros().scale()<=0; case "number" -> value.isNumber(); case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false; };
            if(!matches) return false;
        }
        if(schema.has("const") && !RuleEvaluator.equal(value,schema.get("const"))) return false;
        if(schema.has("enum")) { boolean found=false; for(var item:schema.get("enum")) found|=RuleEvaluator.equal(value,item); if(!found) return false; }
        if(value.isObject()) {
            for(var key:schema.path("required")) if(!value.has(key.asText())) return false;
            var properties=schema.path("properties");
            for(var fields=value.fields();fields.hasNext();) { var field=fields.next(); if(++work[0]>4096) throw new IllegalArgumentException("Schema evaluation budget exceeded"); if(properties.has(field.getKey())) { if(!matches(field.getValue(),properties.get(field.getKey()),work)) return false; } else if(schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean()) return false; }
        }
        if(value.isArray()) { if(!bound(value.size(),schema,"minItems","maxItems")) return false; if(schema.has("items")) for(var item:value) if(!matches(item,schema.get("items"),work)) return false; }
        if(value.isTextual() && !bound(value.asText().codePointCount(0,value.asText().length()),schema,"minLength","maxLength")) return false;
        if(value.isNumber()) { var number=RuleEvaluator.number(value); if(schema.has("minimum") && number.compareTo(RuleEvaluator.number(schema.get("minimum")))<0) return false; if(schema.has("maximum") && number.compareTo(RuleEvaluator.number(schema.get("maximum")))>0) return false; }
        return true;
    }
    private boolean bound(int size,JsonNode schema,String min,String max) { return (!schema.has(min) || size>=schema.get(min).asInt()) && (!schema.has(max) || size<=schema.get(max).asInt()); }
}
