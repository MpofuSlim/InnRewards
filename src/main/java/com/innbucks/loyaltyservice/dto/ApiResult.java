package com.innbucks.loyaltyservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "ApiResponse", description = "Standard response envelope for all endpoints.")
public class ApiResult<T> {

    @Schema(example = "200 OK", description = "HTTP status code plus reason phrase, e.g. '200 OK', '404 NOT_FOUND'.")
    private String code;

    @Schema(example = "Request completed successfully", description = "Human-readable summary of the outcome.")
    private String message;

    @Schema(description = "Response payload; null when there's nothing to return (errors, 204, etc.).")
    private T data;

    public static <T> ApiResult<T> of(HttpStatus status, String message, T data) {
        return ApiResult.<T>builder()
                .code(status.value() + " " + status.name())
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResult<T> ok(String message, T data) {
        return of(HttpStatus.OK, message, data);
    }

    public static <T> ApiResult<T> ok(T data) {
        return of(HttpStatus.OK, "OK", data);
    }

    public static <T> ApiResult<T> created(String message, T data) {
        return of(HttpStatus.CREATED, message, data);
    }

    public static <T> ApiResult<T> error(HttpStatus status, String message) {
        return of(status, message, null);
    }
}
