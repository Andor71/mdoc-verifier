package com.mdoc.verifier.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/** The original stream is used up after buffering; this serves the same bytes again. */
public final class BufferedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public BufferedBodyRequest(final HttpServletRequest request, final byte[] body) {
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
