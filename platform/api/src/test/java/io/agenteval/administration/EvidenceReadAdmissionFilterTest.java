package io.agenteval.administration;

import jakarta.servlet.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class EvidenceReadAdmissionFilterTest {
    MockHttpServletRequest request(String user) {
        var request=new MockHttpServletRequest("GET","/api/v1/evidence/snapshots/00000000-0000-0000-0000-000000000001"); request.setUserPrincipal(() -> user); return request;
    }
    FilterChain hold(CountDownLatch entered,CountDownLatch release) {
        return (request,response) -> { entered.countDown(); try { if(!release.await(10,TimeUnit.SECONDS)) throw new ServletException("Fixture release timeout"); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new ServletException(failure); } };
    }
    @Test void limitsTwoConcurrentLargeReadsAndRecoversSlots() throws Exception {
        var filter=new EvidenceReadAdmissionFilter(); var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(() -> { filter.doFilter(request("one"),new MockHttpServletResponse(),hold(entered,release)); return true; });
            var second=pool.submit(() -> { filter.doFilter(request("two"),new MockHttpServletResponse(),hold(entered,release)); return true; });
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); var rejected=new MockHttpServletResponse(); filter.doFilter(request("three"),rejected,(request,response)->{});
                assertThat(rejected.getStatus()).isEqualTo(429); assertThat(rejected.getContentAsString()).contains("CAPACITY_REACHED");
            } finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5,TimeUnit.SECONDS)).isTrue();
        }
        var allowed=new MockHttpServletResponse(); filter.doFilter(request("three"),allowed,(request,response)->{}); assertThat(allowed.getStatus()).isEqualTo(200);
    }
    @Test void preventsOneUserOccupyingBothLargeReadSlots() throws Exception {
        var filter=new EvidenceReadAdmissionFilter(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(() -> { filter.doFilter(request("one"),new MockHttpServletResponse(),hold(entered,release)); return true; });
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); var duplicate=new MockHttpServletResponse(); filter.doFilter(request("one"),duplicate,(request,response)->{}); assertThat(duplicate.getStatus()).isEqualTo(429);
                var other=new MockHttpServletResponse(); filter.doFilter(request("two"),other,(request,response)->{}); assertThat(other.getStatus()).isEqualTo(200);
            } finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void releasesAfterSerializationFailureAndLeavesPagedTraceReadsUnaffected() throws Exception {
        var filter=new EvidenceReadAdmissionFilter();
        assertThatThrownBy(() -> filter.doFilter(request("one"),new MockHttpServletResponse(),(request,response)->{ throw new ServletException("Disconnected client"); })).isInstanceOf(ServletException.class);
        var allowed=new MockHttpServletResponse(); filter.doFilter(request("one"),allowed,(request,response)->{}); assertThat(allowed.getStatus()).isEqualTo(200);
        var trace=new MockHttpServletRequest("GET","/api/v1/traces/00000000-0000-0000-0000-000000000001"); var response=new MockHttpServletResponse(); filter.doFilter(trace,response,(request,result)->{}); assertThat(response.getStatus()).isEqualTo(200);
    }
}
