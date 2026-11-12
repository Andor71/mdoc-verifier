package com.mdoc.verifier.filter;

import static com.mdoc.verifier.support.MdocFixtures.deviceResponse;
import static com.mdoc.verifier.support.MdocFixtures.document;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.mdoc.verifier.support.Cbor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The parts only a real Tomcat has: chunked request bodies and the RemoteIpValve that turns X-Forwarded-For into
 * getRemoteAddr(). The test client connects from 127.0.0.1, a trusted proxy by default, the same role the ALB
 * plays in production. Every test uses its own client IP, because the buckets outlive a single test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "mdoc.limits.rate-limit.capacity=2",
        "mdoc.limits.max-body-bytes=2000"})
class RequestLimitsEndToEndTest {

    private static final String VALID_REQUEST =
            "{\"mdoc\":\"" + Cbor.hex(deviceResponse(document())) + "\",\"inputType\":\"HEX\"}";

    // HTTP/1.1 explicitly: chunked transfer encoding is an HTTP/1.1 thing.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    private HttpResponse<String> postDecode(final HttpRequest.BodyPublisher body, final String forwardedFor)
            throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/decode"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(body)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postDecode(final String forwardedFor) throws Exception {
        return postDecode(HttpRequest.BodyPublishers.ofString(VALID_REQUEST), forwardedFor);
    }

    /** A stream of unknown length: the client cannot send Content-Length, so it sends the body chunked. */
    private static HttpRequest.BodyPublisher chunked(final String body) {
        final HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(publisher.contentLength()).as("unknown length, so chunked").isNegative();
        return publisher;
    }

    @Test
    void chunkedBodyUnderTheLimitReachesTheController() throws Exception {
        final HttpResponse<String> response = postDecode(chunked(VALID_REQUEST), "203.0.113.30");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"version\":\"1.0\"");
    }

    @Test
    void chunkedBodyOverTheLimitIs413() throws Exception {
        final String tooLarge = "{\"mdoc\":\"" + "a".repeat(2000) + "\",\"inputType\":\"HEX\"}";

        final HttpResponse<String> response = postDecode(chunked(tooLarge), "203.0.113.31");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("REQUEST_TOO_LARGE");
    }

    @Test
    void forwardedForIsTheClientIdentity() throws Exception {
        assertThat(postDecode("203.0.113.32").statusCode()).isEqualTo(200);
        assertThat(postDecode("203.0.113.32").statusCode()).isEqualTo(200);
        assertThat(postDecode("203.0.113.32").statusCode()).isEqualTo(429);

        // Same TCP peer (127.0.0.1), other forwarded client: its own bucket. Without the RemoteIpValve
        // (forward-headers-strategy removed), every request would count against 127.0.0.1 and this would be 429.
        assertThat(postDecode("203.0.113.33").statusCode()).isEqualTo(200);
    }

    @Test
    void spoofedLeftmostForwardedForDoesNotBypassTheLimit() throws Exception {
        // The client writes the left part itself; the proxy appends the address it saw (203.0.113.34).
        // Tomcat reads right to left and stops at the first untrusted address, so the fake values are ignored.
        assertThat(postDecode("6.6.6.1, 203.0.113.34").statusCode()).isEqualTo(200);
        assertThat(postDecode("6.6.6.2, 203.0.113.34").statusCode()).isEqualTo(200);

        final HttpResponse<String> third = postDecode("6.6.6.3, 203.0.113.34");

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Retry-After")).isPresent();
    }
}
