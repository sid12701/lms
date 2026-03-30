package com.bhawana.lms.web;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.service.AdminDirectoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/users")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class UserAdminController {

    private final AdminDirectoryService adminDirectoryService;

    public UserAdminController(AdminDirectoryService adminDirectoryService) {
        this.adminDirectoryService = adminDirectoryService;
    }

    @GetMapping
    public List<UserResponse> listUsers() {
        return adminDirectoryService.listUsers().stream()
                .map(UserAdminController::toResponse)
                .toList();
    }

    @PostMapping
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        AppUser user = adminDirectoryService.createUser(
                request.username(),
                request.email(),
                request.password(),
                request.status(),
                request.lspId(),
                request.roles()
        );
        return toResponse(user);
    }

    private static UserResponse toResponse(AppUser user) {
        return new UserResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getStatus().name(),
                user.getLsp() == null ? null : user.getLsp().getId().toString(),
                user.getLsp() == null ? "All LSPs" : user.getLsp().getName(),
                user.getRoles().stream().map(role -> role.getCode().name()).sorted().toList()
        );
    }

    public record CreateUserRequest(
            @NotBlank String username,
            @Email @NotBlank String email,
            @NotBlank String password,
            UserStatus status,
            UUID lspId,
            @NotEmpty Set<RoleCode> roles
    ) {
        public CreateUserRequest {
            if (status == null) {
                status = UserStatus.ACTIVE;
            }
        }
    }

    public record UserResponse(
            String id,
            String username,
            String email,
            String status,
            String lspId,
            String lspName,
            List<String> roles
    ) {
    }
}
