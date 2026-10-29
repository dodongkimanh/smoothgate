package com.smitgate.auth;

import com.smitgate.common.ApiResponse;
import com.smitgate.connector.ads.AdAccount;
import com.smitgate.connector.ads.AdAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/** Admin-only (enforced in SecurityConfig): manage staff users and their assigned ad accounts. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserRepository userRepository;
    private final UserAdAccountRepository userAdAccountRepository;
    private final AdAccountRepository adAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<User> users = userRepository.findByTenantId(tenantId).stream()
                .sorted(Comparator.comparing(User::getId))
                .toList();
        Map<Long, List<Long>> assignments = userAdAccountRepository
                .findByUserIdIn(users.stream().map(User::getId).toList()).stream()
                .collect(Collectors.groupingBy(UserAdAccount::getUserId,
                        Collectors.mapping(UserAdAccount::getAdAccountId, Collectors.toList())));
        return ResponseEntity.ok(ApiResponse.ok(users.stream()
                .map(u -> toView(u, assignments.getOrDefault(u.getId(), List.of())))
                .toList()));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String email = str(body.get("email"));
        String password = str(body.get("password"));
        if (email.isBlank() || !email.contains("@")) throw new IllegalArgumentException("Email không hợp lệ");
        if (password.length() < 8) throw new IllegalArgumentException("Mật khẩu phải có ít nhất 8 ký tự");
        if (userRepository.existsByEmail(email)) throw new IllegalArgumentException("Email đã được sử dụng");

        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName(str(body.get("fullName")).isBlank() ? email : str(body.get("fullName")));
        user.setRole(parseRole(body.get("role")));
        user.setStatus("ACTIVE");
        user = userRepository.save(user);

        List<Long> accountIds = saveAssignments(tenantId, user, body.get("adAccountIds"));
        return ResponseEntity.ok(ApiResponse.ok(toView(user, accountIds)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        User user = findInTenant(tenantId, id);
        boolean isSelf = user.getEmail().equalsIgnoreCase(currentEmail());

        if (body.containsKey("fullName") && !str(body.get("fullName")).isBlank()) {
            user.setFullName(str(body.get("fullName")));
        }
        if (body.containsKey("password") && !str(body.get("password")).isBlank()) {
            if (str(body.get("password")).length() < 8) {
                throw new IllegalArgumentException("Mật khẩu phải có ít nhất 8 ký tự");
            }
            user.setPasswordHash(passwordEncoder.encode(str(body.get("password"))));
        }
        if (body.containsKey("role")) {
            User.Role role = parseRole(body.get("role"));
            if (isSelf && role == User.Role.STAFF) {
                throw new IllegalArgumentException("Không thể tự hạ quyền tài khoản đang đăng nhập");
            }
            user.setRole(role);
        }
        if (body.containsKey("status")) {
            String status = "INACTIVE".equals(str(body.get("status"))) ? "INACTIVE" : "ACTIVE";
            if (isSelf && "INACTIVE".equals(status)) {
                throw new IllegalArgumentException("Không thể khóa tài khoản đang đăng nhập");
            }
            user.setStatus(status);
        }
        user = userRepository.save(user);

        List<Long> accountIds;
        if (body.containsKey("adAccountIds")) {
            userAdAccountRepository.deleteByUserId(user.getId());
            accountIds = saveAssignments(tenantId, user, body.get("adAccountIds"));
        } else {
            accountIds = userAdAccountRepository.findByUserId(user.getId()).stream()
                    .map(UserAdAccount::getAdAccountId).toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(toView(user, accountIds)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(HttpServletRequest request, @PathVariable Long id) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        User user = findInTenant(tenantId, id);
        if (user.getEmail().equalsIgnoreCase(currentEmail())) {
            throw new IllegalArgumentException("Không thể xóa tài khoản đang đăng nhập");
        }
        userAdAccountRepository.deleteByUserId(user.getId());
        userRepository.delete(user);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", id)));
    }

    private List<Long> saveAssignments(Long tenantId, User user, Object rawIds) {
        if (user.getRole() != User.Role.STAFF || !(rawIds instanceof List<?> list)) return List.of();
        Set<Long> tenantAccountIds = adAccountRepository.findByTenantId(tenantId).stream()
                .map(AdAccount::getId).collect(Collectors.toSet());
        List<Long> ids = list.stream()
                .map(v -> Long.valueOf(v.toString()))
                .filter(tenantAccountIds::contains)
                .distinct()
                .toList();
        userAdAccountRepository.saveAll(ids.stream().map(aid -> new UserAdAccount(user.getId(), aid)).toList());
        return ids;
    }

    private User findInTenant(Long tenantId, Long id) {
        return userRepository.findById(id)
                .filter(u -> Objects.equals(u.getTenantId(), tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng"));
    }

    private static User.Role parseRole(Object raw) {
        return "ADMIN".equals(str(raw)) ? User.Role.ADMIN : User.Role.STAFF;
    }

    private static String currentEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static Map<String, Object> toView(User u, List<Long> adAccountIds) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", u.getId());
        view.put("email", u.getEmail());
        view.put("fullName", u.getFullName());
        view.put("role", u.getRole().name());
        view.put("status", u.getStatus());
        view.put("createdAt", u.getCreatedAt());
        view.put("adAccountIds", adAccountIds);
        return view;
    }
}
