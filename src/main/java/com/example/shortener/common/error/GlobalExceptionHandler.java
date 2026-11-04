package com.example.shortener.common.error;

import com.example.shortener.common.web.TraceIdFilter;
import com.example.shortener.security.InvalidUrlException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/// The single place where exceptions become HTTP responses. **Every** error, whether raised by our code or by
/// Spring (bad JSON, unknown route, wrong method), leaves in one uniform shape: an RFC 9457 problem detail with
/// three extension fields.
///
/// ```json
/// { "type": "https://errors.shortener.example/link-not-found", "title": "Link not found", "status": 404,
///   "detail": "No link with code 'abc'", "instance": "/api/v1/links/abc",
///   "errorCode": "LINK_NOT_FOUND", "timestamp": "2026-10-08T02:40:00Z", "traceId": "4bf92f3577b34da6" }
/// ```
/// Validation failures add `errors: [{field, message}]`.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------ domain and infrastructure errors

    @ExceptionHandler(InvalidUrlException.class)
    ResponseEntity<Object> invalidUrl(InvalidUrlException ex, WebRequest request) {
        return render(new ApiException(ErrorCode.INVALID_URL, ex.getMessage()), request);
    }

    /// Pool exhausted or database down: fail fast with 503 so clients and load balancers back off.
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        TransientDataAccessException.class,
        CannotCreateTransactionException.class
    })
    ResponseEntity<Object> databaseUnavailable(RuntimeException ex, WebRequest request) {
        log.warn("Database unavailable: {}", ex.getMessage());
        return render(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Storage is temporarily unavailable"), request);
    }

    /// Safety net for bugs: full stack trace in the log, nothing internal in the response.
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return render(
                new ApiException(
                        ErrorCode.INTERNAL_ERROR, "Unexpected error. Quote the traceId when reporting this problem."),
                request);
    }

    // ------------------------------------------------------------------ validation details

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setType(URI.create(ApiException.TYPE_PREFIX + ErrorCode.VALIDATION_FAILED.slug()));
        body.setTitle(ErrorCode.VALIDATION_FAILED.title());
        body.setDetail("Request body has invalid fields");
        body.setProperty("errorCode", ErrorCode.VALIDATION_FAILED.name());
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // ------------------------------------------------------------------ uniform enrichment (all paths end here)

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getType() == null
                    || "about:blank".equals(problem.getType().toString())) {
                problem.setType(URI.create(ApiException.TYPE_PREFIX
                        + ErrorCode.forStatus(statusCode.value()).slug()));
            }
            Map<String, Object> props = problem.getProperties();
            if (props == null || !props.containsKey("errorCode")) {
                problem.setProperty(
                        "errorCode", ErrorCode.forStatus(statusCode.value()).name());
            }
            if (request instanceof ServletWebRequest servlet) {
                problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
            }
            problem.setProperty("timestamp", Instant.now().toString());
            problem.setProperty("traceId", MDC.get(TraceIdFilter.MDC_KEY));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> render(ApiException ex, WebRequest request) {
        return handleErrorResponseException(ex, ex.getHeaders(), ex.getStatusCode(), request);
    }
}
