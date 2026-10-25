package io.agenteval.sdk;
import io.agenteval.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SpoolRecoveryTest {
    @TempDir Path directory;
    @Test void replaysSameIdsWithoutLeakingSecrets() throws Exception {
        var path=directory.resolve("queue.sqlite"); var trial=UUID.randomUUID(); List<UUID> ids;
        try(var spool=new FileSpool(path,2048)) {
            var telemetry=new Telemetry(trial,UUID.randomUUID().toString().replace("-",""),spool,List.of("private-secret"));
            try(var active=telemetry.activate()) { assertThat(Telemetry.tool("query",() -> Map.of("password","private-secret","amount",100))).containsEntry("amount",100); }
            ids=spool.pending(trial).stream().map(TraceEvent::eventId).toList();
            assertThat(spool.markExecution(trial,"sensitive-attempt")).isTrue();
            var huge=new TraceEvent("1.0",UUID.randomUUID(),trial,"1".repeat(32),"1".repeat(16),null,EventType.LOG,SpanKind.CUSTOM,"large",1,Instant.now(),JsonSupport.tree("\""+"x".repeat(4096)+"\""),new CaptureMetadata(false,false));
            assertThat(spool.append(huge)).isFalse(); assertThat(spool.stats(trial).droppedCount()).isPositive();
        }
        try(var spool=new FileSpool(path,2048)) {
            assertThat(spool.pending(trial).stream().map(TraceEvent::eventId)).containsExactlyElementsOf(ids);
            assertThat(spool.markExecution(trial,"sensitive-attempt")).isFalse();
            spool.acknowledge(trial,List.of(UUID.randomUUID())); assertThat(spool.pending(trial)).hasSize(ids.size());
            spool.acknowledge(trial,ids); assertThat(spool.pending(trial)).isEmpty();
        }
        var bytes=new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.ISO_8859_1); assertThat(bytes).doesNotContain("private-secret","sensitive-attempt");
    }
}
