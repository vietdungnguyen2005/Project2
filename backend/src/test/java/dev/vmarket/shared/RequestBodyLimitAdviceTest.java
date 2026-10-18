package dev.vmarket.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class RequestBodyLimitAdviceTest {
    @Test
    void aConverterCannotBypassTheBudgetBySkippingInputBytes() throws Exception {
        var source = new ByteArrayInputStream(new byte[100_000]);
        var message = new HttpInputMessage() {
            public InputStream getBody() {
                return source;
            }

            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        var parameter = new MethodParameter(getClass().getDeclaredMethod("json", Map.class), 0);
        var bounded = new RequestBodyLimitAdvice()
                .beforeBodyRead(message, parameter, Map.class, MappingJackson2HttpMessageConverter.class);
        assertThatThrownBy(() -> bounded.getBody().skip(100_000))
                .isInstanceOf(RequestBodyLimitAdvice.PayloadTooLarge.class);
        assertThat(100_000 - source.available()).isEqualTo(65_537);
    }

    @Test
    void anUnknownLengthJsonBodyIsStoppedAfterAtMostOneOverflowByte() throws Exception {
        byte[] bytes = new byte[100_000];
        var source = new ByteArrayInputStream(bytes);
        var message = new HttpInputMessage() {
            public InputStream getBody() {
                return source;
            }

            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        var parameter = new MethodParameter(getClass().getDeclaredMethod("json", Map.class), 0);
        var bounded = new RequestBodyLimitAdvice()
                .beforeBodyRead(message, parameter, Map.class, MappingJackson2HttpMessageConverter.class);
        assertThatThrownBy(() -> bounded.getBody().readAllBytes())
                .isInstanceOf(RequestBodyLimitAdvice.PayloadTooLarge.class);
        assertThat(bytes.length - source.available()).isEqualTo(65_537);
    }

    private void json(Map<String, Object> body) {}
}
