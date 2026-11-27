package com.quorum.placementservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRoundRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull @Min(1) @Max(99) Integer sequence) {
}
