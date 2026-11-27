package com.quorum.checkinservice.controller;

import com.quorum.checkinservice.dto.ErrorResponse;
import com.quorum.checkinservice.dto.ErrorResponse.FieldErrorItem;
import com.quorum.checkinservice.service.ApiException;
import com.quorum.checkinservice.service.ValidationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.core.JacksonException;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Turns every failure into the same JSON shape: status, error, code, message, errors[]. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException ex) {
        List<FieldErrorItem> errors = ex.getBindingResult().getAllErrors().stream()
                .map(e -> new FieldErrorItem(e instanceof FieldError fe ? fe.getField() : e.getObjectName(),
                        e.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldErrorItem::field).thenComparing(FieldErrorItem::message))
                .toList();
        String message = errors.stream()
                .map(e -> e.field() + " " + e.message())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, message, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof JacksonException je && !je.getPath().isEmpty()) {
            String field = je.getPath().stream()
                    .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            return body(HttpStatus.BAD_REQUEST, field + " has an invalid value",
                    List.of(new FieldErrorItem(field, "has an invalid value")));
        }
        return body(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON", List.of());
    }

    /** Constraint annotations on @PathVariable / @RequestParam. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> invalidParameter(HandlerMethodValidationException ex) {
        List<FieldErrorItem> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        // a @Valid body checked together with a constrained path variable: name the body field
                        .map(e -> new FieldErrorItem(e instanceof FieldError fe ? fe.getField()
                                : r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .sorted(Comparator.comparing(FieldErrorItem::field).thenComparing(FieldErrorItem::message))
                .toList();
        String message = errors.stream().map(e -> e.field() + " " + e.message()).collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, message, errors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getParameterName() + " is required",
                List.of(new FieldErrorItem(ex.getParameterName(), "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badPathValue(MethodArgumentTypeMismatchException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getName() + " has an invalid value",
                List.of(new FieldErrorItem(ex.getName(), "has an invalid value")));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> missingPart(MissingServletRequestPartException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getRequestPartName() + " is required",
                List.of(new FieldErrorItem(ex.getRequestPartName(), "is required")));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> badMultipart(MultipartException ex) {
        return body(HttpStatus.BAD_REQUEST, "Expected multipart/form-data with a file part", List.of());
    }

    /** Only the X-User headers are required headers; the interceptor normally answers first. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException ex) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing " + ex.getHeaderName() + " header", List.of());
    }

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<ErrorResponse> validationFailed(ValidationFailedException ex) {
        return body(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getErrors());
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> api(ApiException ex) {
        return body(ex.getStatus(), ex.getCode(), ex.getMessage(), List.of());
    }

    /** Spring's own errors (404 unknown path, 405, 415 ...) keep their status. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> framework(ErrorResponseException ex) {
        return body(ex.getStatusCode(), ex.getBody().getDetail(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse er) {
            return body(er.getStatusCode(), er.getBody().getDetail(), List.of());
        }
        log.error("Unexpected error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", List.of());
    }

    /** code = the status name (BAD_REQUEST, NOT_FOUND ...) when no specific code applies. */
    private static ResponseEntity<ErrorResponse> body(HttpStatusCode status, String message,
                                                      List<FieldErrorItem> errors) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return body(status, resolved != null ? resolved.name() : String.valueOf(status.value()), message, errors);
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatusCode status, String code, String message,
                                                      List<FieldErrorItem> errors) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String reason = resolved != null ? resolved.getReasonPhrase() : String.valueOf(status.value());
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), reason, code, message, errors));
    }
}
