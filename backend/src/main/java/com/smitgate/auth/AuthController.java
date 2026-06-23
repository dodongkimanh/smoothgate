package com.smitgate.auth;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthResponse>> me(HttpServletRequest request) {
        String email = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        AuthResponse response = new AuthResponse(
                null, user.getEmail(), user.getFullName(),
                user.getRole().name(), user.getTenantId()
        );
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/auth/change-password")
    public ResponseEntity<ApiResponse<Map<String, String>>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        String email = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        authService.changePassword(email, request);
        return ResponseEntity.ok(ApiResponse.ok(java.util.Map.of("message", "Đổi mật khẩu thành công")));
    }
}
