package io.agenteval.examples;
import io.agenteval.sdk.*;
import java.time.Instant;
import java.util.*;
public final class OrderAgent {
    public static AgentResult handle(TaskContext task) {
        if(task.cancelled()) return AgentResult.of(Map.of("status","cancelled"));
        return AgentResult.of(Telemetry.tool("query_order",() -> task.input().path("order_id").asText().equals("A")?Map.of("amount",100,"status","paid"):Map.of("status","not_found")));
    }
    public static void main(String[] args) {
        if(Arrays.asList(args).contains("--local")) {
            var task=new TaskContext(UUID.randomUUID(),"Query order A",JsonSupport.tree("{\"order_id\":\"A\"}"),Instant.now().plusSeconds(60),new CancellationToken());
            System.out.println(JsonSupport.encode(handle(task).output())); return;
        }
        var agent=System.getenv("AGENT_EVAL_AGENT_ID"); if(agent==null) throw new IllegalArgumentException("AGENT_EVAL_AGENT_ID is required");
        try(var worker=EvaluationWorker.fromEnvironment()) {
            worker.registerAgent(agent,OrderAgent::handle); Runtime.getRuntime().addShutdownHook(new Thread(worker::close)); worker.startAndAwait();
        }
    }
}
