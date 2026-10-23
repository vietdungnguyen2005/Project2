package dev.vmarket.migration;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops/integration-profiles")
public class IntegrationProfileController {
    private final IntegrationProfileService profiles;
    private final OpsAuthorizer authorizer;

    public IntegrationProfileController(IntegrationProfileService profiles, OpsAuthorizer authorizer) {
        this.profiles = profiles;
        this.authorizer = authorizer;
    }

    @GetMapping
    List<IntegrationProfileService.Profile> recent(@RequestHeader("X-V-Market-Ops-Secret") String secret) {
        authorizer.authorize(secret);
        return profiles.recent();
    }

    @PostMapping
    org.springframework.http.ResponseEntity<IntegrationProfileService.Profile> publish(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @RequestBody IntegrationProfileService.Definition definition,
            java.security.Principal actor) {
        authorizer.authorize(secret);
        return org.springframework.http.ResponseEntity.status(201).body(profiles.publish(definition, actor.getName()));
    }

    public record Activation(boolean active) {}

    @PatchMapping("/{profileId}/{version}")
    IntegrationProfileService.Profile activate(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @PathVariable String profileId,
            @PathVariable int version,
            @RequestBody Activation request,
            java.security.Principal actor) {
        authorizer.authorize(secret);
        return profiles.activate(profileId, version, request.active(), actor.getName());
    }
}
