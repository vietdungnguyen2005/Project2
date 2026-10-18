package dev.vmarket.shared;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Bound controller body reads before byte-array allocation or JSON/CSV parsing, including chunked requests. */
@ControllerAdvice
public class RequestBodyLimitAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage message,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter)
            throws IOException {
        int limit = parameter.getParameterType() == byte[].class
                ? RequestBodyLimits.CSV_MAX_BYTES
                : RequestBodyLimits.JSON_MAX_BYTES;
        if (message.getHeaders().getContentLength() > limit) throw new PayloadTooLarge();
        var bounded = new FilterInputStream(message.getBody()) {
            private int consumed;

            @Override
            public int read() throws IOException {
                if (consumed > limit) throw new PayloadTooLarge();
                int value = in.read();
                if (value >= 0 && ++consumed > limit) throw new PayloadTooLarge();
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (consumed > limit) throw new PayloadTooLarge();
                // At most one byte beyond the limit is consumed to detect overflow.
                int count = in.read(buffer, offset, Math.min(length, limit - consumed + 1));
                if (count > 0 && (consumed += count) > limit) throw new PayloadTooLarge();
                return count;
            }

            @Override
            public long skip(long count) throws IOException {
                if (consumed > limit) throw new PayloadTooLarge();
                if (count <= 0) return 0;
                long skipped = in.skip(Math.min(count, limit - consumed + 1L));
                consumed += (int) skipped;
                if (consumed > limit) throw new PayloadTooLarge();
                return skipped;
            }
        };
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return bounded;
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }

    public static final class PayloadTooLarge extends RuntimeException {
        PayloadTooLarge() {
            super("Request body exceeds the allowed byte limit.");
        }
    }
}
