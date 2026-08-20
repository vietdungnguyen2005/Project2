package dev.vmarket.shared;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProductionConfigurationGuard {
    private final String environment;
    private final String bffSecret;
    private final String opsSecret;

    public ProductionConfigurationGuard(
            @Value("${vmarket.deployment-environment:local}") String environment,
            @Value("${vmarket.bff-secret}") String bffSecret,
            @Value("${vmarket.ops-secret}") String opsSecret) {
        this.environment = environment;
        this.bffSecret = bffSecret;
        this.opsSecret = opsSecret;
    }

    @PostConstruct
    void rejectDevelopmentSecretsInProduction() {
        if (!"production".equalsIgnoreCase(environment)) {
            return;
        }
        if (bffSecret.length() < 32 || "local-bff-secret".equals(bffSecret)) {
            throw new IllegalStateException("BFF_SHARED_SECRET must be a strong production secret.");
        }
        if (opsSecret.length() < 32 || "local-development-only".equals(opsSecret)) {
            throw new IllegalStateException("VMARKET_OPS_SECRET must be a strong production secret.");
        }
    }
}
