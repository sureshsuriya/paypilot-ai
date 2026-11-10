package com.paypilot.security;

import com.paypilot.model.User;
import com.paypilot.model.UserRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security principal holding authenticated user identity.
 * Controllers derive ownership solely from getId(), never from request bodies.
 */
@Getter
public class AuthenticatedUser implements UserDetails {

    private final Long id;
    private final String email;
    private final String password;
    private final UserRole role;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedUser(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.password = user.getPasswordHash();
        this.role = user.getRole();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }
}

