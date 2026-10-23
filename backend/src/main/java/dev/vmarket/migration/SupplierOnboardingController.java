package dev.vmarket.migration;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops/onboarding/{profileId}/{version}")
public class SupplierOnboardingController {
    private final SupplierOnboardingService onboarding;
    private final OpsAuthorizer authorizer;

    public SupplierOnboardingController(SupplierOnboardingService onboarding, OpsAuthorizer authorizer) {
        this.onboarding = onboarding;
        this.authorizer = authorizer;
    }

    @PostMapping(value = "/preview", consumes = "text/csv")
    SupplierOnboardingService.Preview preview(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @PathVariable String profileId,
            @PathVariable int version,
            @RequestBody byte[] bytes,
            java.security.Principal actor) {
        authorizer.authorize(secret);
        return onboarding.preview(profileId, version, bytes, actor.getName());
    }

    @PostMapping(value = "/confirm", consumes = "text/csv")
    org.springframework.http.ResponseEntity<MigrationSummary> confirm(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @PathVariable String profileId,
            @PathVariable int version,
            @RequestBody byte[] bytes,
            @RequestHeader("X-Source-Name") String sourceName,
            @RequestHeader(value = "X-Preview-Receipt", required = false) String receipt,
            java.security.Principal actor) {
        authorizer.authorize(secret);
        return org.springframework.http.ResponseEntity.accepted()
                .header("Preference-Applied", "respond-async")
                .body(onboarding.confirm(profileId, version, bytes, sourceName, receipt, actor.getName()));
    }
}
