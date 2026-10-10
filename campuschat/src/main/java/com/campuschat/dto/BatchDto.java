package com.campuschat.dto;

import java.time.Instant;

/**
 * Data transfer object for academic batches.
 */
public record BatchDto(
    Integer batchId,
    String name,
    Integer year,
    Instant createdAt
) {}
