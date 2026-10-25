package io.agenteval.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.UUID;

/** Strict wire codec shared by Java platform and SDK. Unknown fields fail closed. */
public final class ProtocolCodec {
    private final ObjectMapper mapper;
    public ProtocolCodec() {
        var ids = new SimpleModule();
        ids.addDeserializer(UUID.class, new JsonDeserializer<UUID>() {
            @Override public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String value = parser.getValueAsString();
                if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                    throw context.weirdStringException(value, UUID.class, "Canonical UUID required");
                return UUID.fromString(value);
            }
        });
        mapper = JsonMapper.builder().addModule(new JavaTimeModule()).addModule(ids)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }
    public String encode(Object value) throws IOException { return mapper.writeValueAsString(value); }
    public <T> T decode(String value, Class<T> type) throws IOException { return mapper.readValue(value,type); }
    public JsonNode tree(String value) throws IOException { return mapper.readTree(value); }
}
