package com.innbucks.loyaltyservice.dto;

// Mirrors user-service's ApiResult envelope shape so we can deserialize
// { code, message, data } responses without depending on a shared module.
public record UserServiceApiResult<T>(String code, String message, T data) {}
