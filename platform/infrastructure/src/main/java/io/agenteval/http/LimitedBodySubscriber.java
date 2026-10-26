package io.agenteval.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

public final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final int limit;
    private Subscription subscription;
    public LimitedBodySubscriber(int limit) { this.limit=limit; }
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Subscription value) { subscription=value; subscription.request(1); }
    public void onNext(List<ByteBuffer> chunks) {
        for(var chunk:chunks) {
            if(chunk.remaining()>limit-bytes.size()) {
                subscription.cancel(); body.completeExceptionally(new IllegalArgumentException("HTTP response limit exceeded")); return;
            }
            byte[] data=new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
        }
        subscription.request(1);
    }
    public void onError(Throwable failure) { body.completeExceptionally(failure); }
    public void onComplete() { body.complete(bytes.toByteArray()); }
}
