package com.pcupgradelab.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.pcupgradelab.common.ApiException;
import java.util.List;

/** 프런트와 합의한 로그인 상태·제공자·로그아웃 API. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final CurrentUser currentUser;
    private final UserAccountRepository users;
    private final SocialAccountRepository socialAccounts;

    public AuthController(CurrentUser currentUser, UserAccountRepository users,
                          SocialAccountRepository socialAccounts) {
        this.currentUser = currentUser;
        this.users = users;
        this.socialAccounts = socialAccounts;
    }

    public record Me(Long id, String name, String email, String provider) { }

    @GetMapping("/me")
    public Me me() {
        var id = currentUser.id().orElseThrow(AuthController::unauthorized);
        var user = users.findById(id).orElseThrow(AuthController::unauthorized);
        var social = socialAccounts.findFirstByUserIdOrderByIdAsc(id).orElseThrow(AuthController::unauthorized);
        return new Me(user.getId(), user.getName(), user.getEmail(), social.getProvider());
    }

    @GetMapping("/providers")
    public List<String> providers() { return List.of("google"); }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
    }
}
