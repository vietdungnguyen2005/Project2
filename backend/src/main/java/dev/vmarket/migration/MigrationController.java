package dev.vmarket.migration;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    @ResponseStatus(HttpStatus.CREATED)
    MigrationSummary upload(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @RequestHeader("X-Source-Name") String sourceName,
            @RequestHeader("X-Source-Encoding") String encoding,
            @RequestBody byte[] bytes) {
        authorizer.authorize(secret);
        return imports.importCsv(sourceName, encoding, bytes);
    }

    @GetMapping
    List<MigrationSummary> recent(@RequestHeader("X-V-Market-Ops-Secret") String secret) {
        authorizer.authorize(secret);
        return imports.recent();
    }

    @GetMapping("/{jobId}")
    MigrationSummary find(@RequestHeader("X-V-Market-Ops-Secret") String secret, @PathVariable UUID jobId) {
        authorizer.authorize(secret);
        return imports.find(jobId);
    }
}
