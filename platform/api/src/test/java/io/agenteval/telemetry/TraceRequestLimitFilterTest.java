package io.agenteval.telemetry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TraceRequestLimitFilterTest {
    @Test void boundsUnknownLengthInputBeforeReadingWholeBody() throws Exception {
        var readCount=new AtomicInteger();
        var request=new MockHttpServletRequest("POST","/api/v1/trials/test/events:batch") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    private final ByteArrayInputStream source=new ByteArrayInputStream(new byte[2*1024*1024]);
                    @Override public int read() { int value=source.read(); if(value!=-1) readCount.incrementAndGet(); return value; }
                    @Override public int read(byte[] bytes,int offset,int length) { int count=source.read(bytes,offset,length); if(count>0) readCount.addAndGet(count); return count; }
                    @Override public boolean isFinished() { return source.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        var response=new MockHttpServletResponse();
        var consumer=new HttpServlet() {
            @Override protected void service(HttpServletRequest request,HttpServletResponse response) throws IOException { request.getInputStream().transferTo(OutputStream.nullOutputStream()); }
        };
        new TraceRequestLimitFilter().doFilter(request,response,new MockFilterChain(consumer));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(readCount.get()).isLessThanOrEqualTo(1024*1024+1);
    }
}
