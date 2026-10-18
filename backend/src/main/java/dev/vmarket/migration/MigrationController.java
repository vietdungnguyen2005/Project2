package dev.vmarket.migration;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops/imports")
public class MigrationController {
    private final LegacyImportService imports;
    private final OpsAuthorizer authorizer;

    public MigrationController(LegacyImportService imports, OpsAuthorizer authorizer) {
        this.imports = imports;
        this.authorizer = authorizer;
    }

    @PostMapping(consumes = "text/csv")
    ResponseEntity<MigrationSummary> upload(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @RequestHeader("X-Source-Name") String sourceName,
            @RequestHeader("X-Source-Encoding") String encoding,
            @RequestHeader(value = "Prefer", required = false) String preference,
            @RequestBody byte[] bytes) {
        authorizer.authorize(secret);
        boolean asynchronous = respondAsync(preference);
        var result = imports.importCsv(sourceName, encoding, bytes, asynchronous);
        return asynchronous
                ? ResponseEntity.accepted()
                        .header("Preference-Applied", "respond-async")
                        .body(result)
                : ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    private boolean respondAsync(String preference) {
        if (preference == null) return false;
        return java.util.Arrays.stream(preference.split(","))
                .anyMatch(value -> value.trim().equalsIgnoreCase("respond-async"));
    }

    @GetMapping
    List<MigrationSummary> recent(@RequestHeader("X-V-Market-Ops-Secret") String secret) {
        authorizer.authorize(secret);
        return imports.recent();
    }

    @PostMapping("/{jobId}/restart")
    ResponseEntity<MigrationSummary> restart(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @PathVariable UUID jobId,
            @RequestHeader(value = "Prefer", required = false) String preference) {
        authorizer.authorize(secret);
        boolean asynchronous = respondAsync(preference);
        var result = imports.restart(jobId, asynchronous);
        if (asynchronous
                && !(result.status().equals("COMPLETED") || result.status().equals("COMPLETED_WITH_ERRORS")))
            return ResponseEntity.accepted()
                    .header("Preference-Applied", "respond-async")
                    .body(result);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{jobId}")
    MigrationSummary find(@RequestHeader("X-V-Market-Ops-Secret") String secret, @PathVariable UUID jobId) {
        authorizer.authorize(secret);
        return imports.find(jobId);
    }
}
