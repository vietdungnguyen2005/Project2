package dev.vmarket.shared;

import dev.vmarket.migration.ImportExecutionException;
import dev.vmarket.migration.InvalidImportException;
import dev.vmarket.migration.OpsUnauthorizedException;
import dev.vmarket.ops.InvalidFulfillmentTransitionException;
import dev.vmarket.order.InvalidOrderException;
import dev.vmarket.order.InventoryConflictException;
import dev.vmarket.order.OrderNotFoundException;
import dev.vmarket.reconciliation.InvalidReconciliationException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidOrderException.class)
    ResponseEntity<ApiError> invalidOrder(InvalidOrderException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_ORDER", exception.getMessage()));
    }

    @ExceptionHandler(InventoryConflictException.class)
    ResponseEntity<ApiError> inventoryConflict(InventoryConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("INVENTORY_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiError> orderNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("ORDER_NOT_FOUND", "Order or tracking token is invalid."));
    }

    @ExceptionHandler(InvalidFulfillmentTransitionException.class)
    ResponseEntity<ApiError> invalidTransition(InvalidFulfillmentTransitionException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_FULFILLMENT_TRANSITION", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        var messages = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "Request validation failed", messages, Instant.now()));
    }

    @ExceptionHandler(InvalidImportException.class)
    ResponseEntity<ApiError> invalidImport(InvalidImportException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_IMPORT", exception.getMessage()));
    }

    @ExceptionHandler(InvalidReconciliationException.class)
    ResponseEntity<ApiError> invalidReconciliation(InvalidReconciliationException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_RECONCILIATION", exception.getMessage()));
    }

    @ExceptionHandler(ImportExecutionException.class)
    ResponseEntity<ApiError> importFailure(ImportExecutionException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("IMPORT_EXECUTION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(OpsUnauthorizedException.class)
    ResponseEntity<ApiError> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("OPS_UNAUTHORIZED", "Operations credentials are invalid."));
    }

    record ApiError(String code, String message, List<String> details, Instant timestamp) {
        static ApiError of(String code, String message) {
            return new ApiError(code, message, List.of(), Instant.now());
        }
    }
}
