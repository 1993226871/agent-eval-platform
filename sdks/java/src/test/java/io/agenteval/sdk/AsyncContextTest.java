package io.agenteval.sdk;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class AsyncContextTest {
    @TempDir Path directory;
    @Test void propagatesOnlyExplicitCapturedContext() throws Exception {
        var trial=UUID.randomUUID();
        try(var spool=new FileSpool(directory.resolve("queue.sqlite")); var executor=Executors.newSingleThreadExecutor()) {
            var telemetry=new Telemetry(trial,UUID.randomUUID().toString().replace("-",""),spool,List.of("private-secret"));
            try(var active=telemetry.activate(); var root=Telemetry.span("agent","root")) {
                var captured=Telemetry.captureContext();
                assertThat(executor.submit(() -> Telemetry.tool("outside",() -> 1)).get()).isEqualTo(1);
                assertThat(executor.submit(() -> Telemetry.runWithContext(captured,() -> Telemetry.tool("lookup",() -> Map.of("api_key","private-secret","amount",100)))).get()).containsEntry("amount",100);
                executor.submit(() -> Telemetry.tool("after-restore",() -> 2)).get();
            }
            var events=spool.pending(trial);
            assertThat(events).hasSize(4);
            var root=events.stream().filter(e -> e.name().equals("root")).findFirst().orElseThrow();
            assertThat(events.stream().filter(e -> e.name().equals("lookup"))).allMatch(e -> e.parentSpanId().equals(root.spanId()));
            assertThat(new String(Files.readAllBytes(directory.resolve("queue.sqlite")),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("private-secret");
        }
    }
}
