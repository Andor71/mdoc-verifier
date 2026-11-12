package com.mdoc.verifier.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.mdoc.verifier.exception.RequestTooLargeException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/**
 * The filter on its own, mainly the chunked path: MockMvc always sets a Content-Length, so it never gets there.
 * With a real Tomcat: {@link RequestLimitsEndToEndTest}.
 */
class BodySizeFilterTest {

    private static final int LIMIT = 10;

    private final AtomicReference<Exception> rejected = new AtomicReference<>();
    private final BodySizeFilter filter = new BodySizeFilter(LIMIT, (request, response, handler, e) -> {
        rejected.set(e);
        return new ModelAndView();
    });

    /** A request without Content-Length, as a chunked upload arrives. */
    private static MockHttpServletRequest chunked(final byte[] body) {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/decode") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }

    private static MockHttpServletRequest chunked(final String body) {
        return chunked(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Runs the filter; returns the request the next element of the chain got, or null if it was stopped. */
    private HttpServletRequest passOn(final MockHttpServletRequest request) throws Exception {
        final MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    void chunkedBodyOverTheLimitIsStopped() throws Exception {
        assertThat(passOn(chunked("12345678901"))).isNull();
        assertThat(rejected.get()).isInstanceOf(RequestTooLargeException.class);
    }

    @Test
    void chunkedBodyAtTheLimitIsServedAgainAfterCounting() throws Exception {
        final HttpServletRequest passed = passOn(chunked("1234567890"));

        assertThat(rejected.get()).isNull();
        assertThat(passed.getInputStream().readAllBytes()).asString().isEqualTo("1234567890");
        assertThat(passed.getContentLengthLong()).isEqualTo(LIMIT); // known now, no longer -1
        assertThat(passed.getContentLength()).isEqualTo(LIMIT);
    }

    /** Runs a roomier filter (100 bytes) and reads the passed-on body through getReader(). */
    private static String readAsText(final MockHttpServletRequest request) throws Exception {
        final BodySizeFilter roomy = new BodySizeFilter(100, (req, res, handler, e) -> new ModelAndView());
        final MockFilterChain chain = new MockFilterChain();
        roomy.doFilter(request, new MockHttpServletResponse(), chain);
        return ((HttpServletRequest) chain.getRequest()).getReader().readLine();
    }

    @Test
    void bufferedBodyReadAsTextDefaultsToUtf8() throws Exception {
        // No charset in the request: UTF-8, the JSON default (RFC 8259 §8.1)
        assertThat(readAsText(chunked("árvíztűrő"))).isEqualTo("árvíztűrő");
    }

    @Test
    void bufferedBodyReadAsTextUsesTheRequestCharset() throws Exception {
        final MockHttpServletRequest request = chunked("árvíz".getBytes(StandardCharsets.ISO_8859_1)); // á = e1, not c3 a1
        request.setCharacterEncoding("ISO-8859-1");

        assertThat(readAsText(request)).isEqualTo("árvíz");
    }

    @Test
    void bufferedStreamReportsItsState() throws Exception {
        final ServletInputStream in = passOn(chunked("12")).getInputStream();

        assertThat(in.isReady()).isTrue(); // all bytes are in memory: a read never blocks
        assertThat(in.isFinished()).isFalse();
        in.readAllBytes();
        assertThat(in.isFinished()).isTrue();
    }

    @Test
    void declaredLengthUnderTheLimitPassesTheOriginalRequestUntouched() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/decode");
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));

        assertThat(passOn(request)).isSameAs(request); // nothing read, so no copy needed
    }

    @Test
    void rejectsEvenWhenNoExceptionHandlerWritesTheResponse() throws Exception {
        final BodySizeFilter unhandled = new BodySizeFilter(LIMIT, (request, response, handler, e) -> null);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockFilterChain chain = new MockFilterChain();

        unhandled.doFilter(chunked("12345678901"), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }
}
