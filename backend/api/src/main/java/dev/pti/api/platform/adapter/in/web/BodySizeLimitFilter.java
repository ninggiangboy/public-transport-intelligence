package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the request body (DOC-31 §3, DOC-27 §5.6): a request that announces more than the limit is refused with 413
 * before its body is read; a body that only turns out too long (chunked) fails with {@link PayloadTooLargeException}
 * when the limit is crossed while it is read.
 */
public final class BodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ProblemWriter problems;

    public BodySizeLimitFilter(long maxBytes, ProblemWriter problems) {
        this.maxBytes = maxBytes;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            problems.write(
                    request,
                    response,
                    ProblemType.PAYLOAD_TOO_LARGE,
                    "The request body is larger than " + maxBytes + " bytes.",
                    Map.of(),
                    Map.of());
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long count;

        LimitedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) {
            count += bytes;
            if (count > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
