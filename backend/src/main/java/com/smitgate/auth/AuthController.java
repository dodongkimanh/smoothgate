package com.smitgate.auth;

import com.smitgate.common.ApiResponse;
import com.smitgate.tenant.Tenant;
import com.smitgate.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final com.smitgate.config.JwtTokenProvider tokenProvider;
    private final AuthenticationManager authenticationManager;

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");
        String fullName = body.getOrDefault("fullName", "User");

        if (email == null || password == null) {
            throw new IllegalArgumentException("Email và mật khẩu không được để trống");
        }

        Tenant tenant = new Tenant();
        tenant.setName(fullName + " Workspace");
        tenant = tenantRepository.save(tenant);

        User user = authService.register(email, password, fullName, tenant.getId());

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        String token = tokenProvider.generateToken(auth, user.getTenantId());

        return ResponseEntity.ok(ApiResponse.ok(new AuthResponse(
                token, user.getEmail(), user.getFullName(),
                user.getRole().name(), user.getTenantId()
        )));
    }

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
