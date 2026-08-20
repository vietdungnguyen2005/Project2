package dev.vmarket.reconciliation;

import dev.vmarket.migration.OpsAuthorizer;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops/reconciliations")
public class ReconciliationController {
    private final ReconciliationService reconciliations;
    private final OpsAuthorizer authorizer;

    public ReconciliationController(ReconciliationService reconciliations, OpsAuthorizer authorizer) {
        this.reconciliations = reconciliations;
        this.authorizer = authorizer;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReconciliationReport create(
            @RequestHeader("X-V-Market-Ops-Secret") String secret, @Valid @RequestBody ReconciliationRequest request) {
        authorizer.authorize(secret);
        return reconciliations.reconcile(request.migrationJobId());
    }

    @GetMapping
    List<ReconciliationReport> recent(@RequestHeader("X-V-Market-Ops-Secret") String secret) {
        authorizer.authorize(secret);
        return reconciliations.recent();
    }
}
