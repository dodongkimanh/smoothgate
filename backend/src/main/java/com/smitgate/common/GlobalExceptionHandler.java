package com.smitgate.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.config.DatabaseHeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.SocketTimeoutException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;
import jakarta.persistence.PersistenceException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;
    private final ObjectProvider<DatabaseHeartbeatService> databaseHeartbeatServiceProvider;

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Email hoặc mật khẩu không đúng"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.error(errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    /**
     * Handle errors when calling external APIs (Pancake, Meta, etc.)
     * Parse the error body to return a meaningful Vietnamese error message.
     */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebClientError(WebClientResponseException e) {
        log.error("External API error: status={}", e.getStatusCode().value());
        String message = parseExternalApiError(e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error(message));
    }

    @ExceptionHandler({
            DataAccessException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            PersistenceException.class,
            InternalAuthenticationServiceException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleDbConnectivity(Exception e) {
        Throwable root = getRootCause(e);
        boolean isConnectivity = isDbConnectivityIssue(root);

        if (isConnectivity) {
            DatabaseHeartbeatService heartbeatService = databaseHeartbeatServiceProvider.getIfAvailable();
            if (heartbeatService != null) {
                heartbeatService.recoverFromAcquireFailure(root.getClass().getSimpleName() + ": " + root.getMessage());
            }
            log.warn("DB connectivity issue: {}", root.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Không thể connect được đến database"));
        }

        log.error("Database access failure: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("Lỗi truy cập dữ liệu"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntime(RuntimeException e) {
        Throwable root = getRootCause(e);
        if (isDbConnectivityIssue(root)) {
            DatabaseHeartbeatService heartbeatService = databaseHeartbeatServiceProvider.getIfAvailable();
            if (heartbeatService != null) {
                heartbeatService.recoverFromAcquireFailure(root.getClass().getSimpleName() + ": " + root.getMessage());
            }
            log.warn("DB connectivity issue via RuntimeException: {}", root.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Không thể connect được đến database"));
        }

        log.error("Unexpected error: {}", e.getMessage(), e);
        String msg = e.getMessage() != null ? e.getMessage() : "Đã xảy ra lỗi, vui lòng thử lại";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(msg));
    }

    private String parseExternalApiError(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        try {
            String body = e.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                JsonNode json = objectMapper.readTree(body);
                // Meta API error format: {"error": {"message": "..."}}
                if (json.has("error") && json.get("error").has("message")) {
                    return "Lỗi API quảng cáo: " + json.get("error").get("message").asText();
                }
                // Poscake / generic format
                if (json.has("message")) {
                    return json.get("message").asText();
                }
                if (json.has("error")) {
                    return json.get("error").asText();
                }
            }
        } catch (Exception ignored) {}

        return switch (status) {
            case 401 -> "API key không hợp lệ hoặc đã hết hạn. Vui lòng kiểm tra lại.";
            case 403 -> "Không có quyền truy cập. Kiểm tra lại API key hoặc token.";
            case 404 -> "Không tìm thấy tài nguyên trên API bên ngoài.";
            case 429 -> "Đã vượt giới hạn số lần gọi API. Vui lòng thử lại sau.";
            default -> "Lỗi kết nối tới API bên ngoài (HTTP " + status + ")";
        };
    }

    private Throwable getRootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private boolean isDbConnectivityIssue(Throwable root) {
        if (root instanceof SQLTransientConnectionException
                || root instanceof SQLNonTransientConnectionException
                || root instanceof SocketTimeoutException) {
            return true;
        }

        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }

        String msg = message.toLowerCase(Locale.ROOT);
        return msg.contains("connection is not available")
                || msg.contains("unable to acquire jdbc connection")
                || msg.contains("unable to open jpa entitymanager")
                || msg.contains("could not open jpa entitymanager")
                || msg.contains("this connection has been closed")
                || msg.contains("connect timed out")
                || msg.contains("hikaripool");
    }
}
