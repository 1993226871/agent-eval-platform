package io.agenteval.protocol;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class SharedSchemaTest {
    @Test void sharedFixturesAgreeWithSchemasAndWireModels() throws Exception {
        var root=Path.of(System.getProperty("contracts.dir"));
        var codec=new ProtocolCodec();
        var manifest=codec.tree(Files.readString(root.resolve("fixtures/v1/manifest.json")));
        for (var item: manifest) {
            var schema=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(codec.tree(Files.readString(root.resolve("schemas/"+item.get("schema").asText()))));
            var payload=codec.tree(Files.readString(root.resolve("fixtures/v1/"+item.get("file").asText())));
            boolean valid=item.get("valid").asBoolean();
            assertThat(schema.validate(payload).isEmpty()).as(item.get("file").asText()).isEqualTo(valid);
            Class<?> type=Class.forName("io.agenteval.protocol."+item.get("type").asText());
            if (valid) assertThat(codec.decode(payload.toString(),type)).isNotNull();
            else assertThatThrownBy(() -> codec.decode(payload.toString(),type)).isInstanceOf(Exception.class);
        }
        assertThat(manifest.size()).isGreaterThanOrEqualTo(12);
    }
}
