package com.mdoc.verifier.filter;

import static com.mdoc.verifier.support.MdocFixtures.deviceResponse;
import static com.mdoc.verifier.support.MdocFixtures.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicReference;

import com.mdoc.verifier.config.FilterConfig;
import com.mdoc.verifier.controller.DecodeController;
import com.mdoc.verifier.controller.HealthController;
import com.mdoc.verifier.exception.GlobalExceptionHandler;
import com.mdoc.verifier.exception.RequestTooLargeException;
import com.mdoc.verifier.service.DecodeService;
import com.mdoc.verifier.service.IssuerAuthService;
import com.mdoc.verifier.service.NameSpaceService;
import com.mdoc.verifier.support.Cbor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Both filters in front of the real controller, with small limits. The buckets live as long as the cached
 * context, so every test uses its own client IP.
 */
@WebMvcTest(controllers = {DecodeController.class, HealthController.class}, properties = {
        "mdoc.limits.rate-limit.capacity=2",
        "mdoc.limits.max-body-bytes=2000"}) // VALID_REQUEST is about 1 KB
@Import({FilterConfig.class, DecodeService.class, NameSpaceService.class, IssuerAuthService.class,
        GlobalExceptionHandler.class})
class RequestLimitFiltersTest {

    private static final String VALID_REQUEST =
            "{\"mdoc\":\"" + Cbor.hex(deviceResponse(document())) + "\",\"inputType\":\"HEX\"}";

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor from(final String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private ResultActions postDecode(final String ip, final String body) throws Exception {
        return mvc.perform(post("/decode").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void requestOverTheBurstIs429WithRetryAfter() throws Exception {
        postDecode("198.51.100.1", VALID_REQUEST).andExpect(status().isOk());
        postDecode("198.51.100.1", VALID_REQUEST).andExpect(status().isOk());

        postDecode("198.51.100.1", VALID_REQUEST)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void eachClientHasItsOwnBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            postDecode("198.51.100.2", VALID_REQUEST);
        }
        postDecode("198.51.100.3", VALID_REQUEST).andExpect(status().isOk());
    }

    @Test
    void healthCheckIsNeverLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/health").with(from("198.51.100.4"))).andExpect(status().isOk());
        }
    }

    @Test
    void bodyOverTheLimitIs413BeforeParsing() throws Exception {
        postDecode("198.51.100.5", "{\"mdoc\":\"" + "a".repeat(2000) + "\",\"inputType\":\"HEX\"}")
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    }

    /** Chunked upload: no Content-Length, so the filter has to count. MockMvc always sets one, hence direct. */
    @Test
    void bodyWithoutContentLengthIsCounted() throws Exception {
        final AtomicReference<Exception> rejected = new AtomicReference<>();
        final BodySizeFilter filter = new BodySizeFilter(10, (request, response, handler, e) -> {
            rejected.set(e);
            return new ModelAndView();
        });

        final MockFilterChain tooLargeChain = new MockFilterChain();
        filter.doFilter(chunked("12345678901"), new MockHttpServletResponse(), tooLargeChain);
        assertThat(rejected.get()).isInstanceOf(RequestTooLargeException.class);
        assertThat(tooLargeChain.getRequest()).isNull(); // never reached the controller

        rejected.set(null);
        final MockFilterChain fitsChain = new MockFilterChain();
        filter.doFilter(chunked("1234567890"), new MockHttpServletResponse(), fitsChain);
        assertThat(rejected.get()).isNull();
        assertThat(fitsChain.getRequest().getInputStream().readAllBytes()).asString().isEqualTo("1234567890");
    }

    private static MockHttpServletRequest chunked(final String body) {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/decode") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body.getBytes());
        return request;
    }
}
