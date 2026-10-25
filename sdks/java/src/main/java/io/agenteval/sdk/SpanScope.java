package io.agenteval.sdk;
import io.agenteval.protocol.*;
import java.util.*;
public final class SpanScope implements AutoCloseable {
    private final Telemetry.Context context;
    private final SpanKind kind;
    private final String name,id;
    private final long started=System.nanoTime();
    private final Telemetry.Scope parent;
    private Object output;
    private String error;
    private boolean closed;
    SpanScope(SpanKind kind,String name,Object input,Telemetry.Context context) {
        if(name==null || name.isBlank() || name.length()>1024) throw new IllegalArgumentException("Invalid span name");
        this.kind=kind; this.name=name; this.context=context; id=UUID.randomUUID().toString().replace("-","").substring(0,16);
        if(context==null) parent=null;
        else { var payload=new HashMap<String,Object>(); payload.put("input",input); context.telemetry().emit(EventType.SPAN_STARTED,kind,name,id,context.parentSpanId(),payload); parent=Telemetry.install(new Telemetry.Context(context.telemetry(),id)); }
    }
    public void output(Object output) { this.output=output; }
    public void failed(Throwable failure) { error=failure.getClass().getSimpleName(); }
    @Override public void close() {
        if(closed) return; closed=true;
        if(context==null) return;
        parent.close(); var payload=new HashMap<String,Object>(); payload.put("duration_ms",Math.max(0,(System.nanoTime()-started)/1_000_000));
        if(error==null) payload.put("output",output); else payload.put("error",Map.of("type",error));
        context.telemetry().emit(error==null?EventType.SPAN_COMPLETED:EventType.SPAN_FAILED,kind,name,id,context.parentSpanId(),payload);
    }
}
