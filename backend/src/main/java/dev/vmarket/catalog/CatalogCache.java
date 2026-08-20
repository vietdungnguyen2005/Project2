package dev.vmarket.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CatalogCache {
    static final String KEY = "vmarket:catalog:v1";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;

    public CatalogCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry metrics) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public List<CatalogQueryService.ProductView> read() {
        try {
            var value = redis.opsForValue().get(KEY);
            if (value == null) {
                metrics.counter("vmarket.catalog.cache", "outcome", "miss").increment();
                return null;
            }
            metrics.counter("vmarket.catalog.cache", "outcome", "hit").increment();
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.counter("vmarket.catalog.cache", "outcome", "error").increment();
            return null;
        }
    }

    public void write(List<CatalogQueryService.ProductView> products) {
        try {
            redis.opsForValue().set(KEY, objectMapper.writeValueAsString(products), TTL);
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.counter("vmarket.catalog.cache", "outcome", "error").increment();
        }
    }

    public void invalidate() {
        try {
            redis.delete(KEY);
        } catch (RuntimeException exception) {
            metrics.counter("vmarket.catalog.cache", "outcome", "error").increment();
        }
    }
}
