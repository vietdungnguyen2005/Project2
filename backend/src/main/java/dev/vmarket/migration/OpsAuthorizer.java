package dev.vmarket.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpsAuthorizer {
    private final byte[] expectedSecret;

    public OpsAuthorizer(@Value("${vmarket.ops-secret}") String expectedSecret) {
        this.expectedSecret = expectedSecret.getBytes(StandardCharsets.UTF_8);
    }

    public void authorize(String provided) {
        if (provided == null || !MessageDigest.isEqual(expectedSecret, provided.getBytes(StandardCharsets.UTF_8))) {
            throw new OpsUnauthorizedException();
        }
    }
}
