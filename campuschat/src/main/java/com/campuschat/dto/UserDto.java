package com.campuschat.dto;

import com.campuschat.model.Role;
import com.campuschat.model.UserStatus;

/**
 * Data transfer object for authenticated users.
 */
public record UserDto(
    Integer userId,
    String username,
    String email,
    Role role,
    UserStatus status,
    Integer batchId
) {}
