package io.agenteval.telemetry;
import java.util.List;
public record TracePage(List<SpanView> spans,String nextCursor) { public TracePage { spans=List.copyOf(spans); } }
