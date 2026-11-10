package com.mdoc.verifier.filter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.mdoc.verifier.exception.RequestTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Rejects bodies over the limit with 413 before Jackson reads them. Without this, {@code @Size} only runs
 * after the whole body is already in memory.
 */
public class BodySizeFilter extends OncePerRequestFilter {

    private final int maxBytes;
    private final HandlerExceptionResolver exceptionResolver;

    public BodySizeFilter(final int maxBytes, final HandlerExceptionResolver exceptionResolver) {
        this.maxBytes = maxBytes;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final long declaredLength = request.getContentLengthLong();

        // 1. Content-Length given: decide from the header, without reading a byte. Tomcat never delivers more
        //    than the declared length, so a body that passes here cannot grow later.
        if (declaredLength > maxBytes) {
            reject(request, response);
            return;
        }
        if (declaredLength >= 0) {
            chain.doFilter(request, response);
            return;
        }

        // 2. No Content-Length (chunked): the size is unknown until read. Read at most one byte past the
        //    limit; if that byte exists, the body is too large. Otherwise hand on the buffered copy.
        final byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            reject(request, response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void reject(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        if (exceptionResolver.resolveException(request, response, null, new RequestTooLargeException(maxBytes)) == null) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
        }
    }

    /** The original stream is used up after buffering; this serves the same bytes again. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(final HttpServletRequest request, final byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(final byte[] buffer, final int offset, final int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(final ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported on a buffered body");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            final String encoding = getCharacterEncoding();
            final Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
