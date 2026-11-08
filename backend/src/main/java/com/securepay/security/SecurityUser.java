package com.securepay.security;

import com.securepay.user.AppUser;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal. Controllers read the user id from here — never from
 * request bodies or AI-generated arguments.
 */
public record SecurityUser(Long id, String username, String passwordHash, String role) implements UserDetails {

    static SecurityUser from(AppUser user) {
        return new SecurityUser(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() { return passwordHash; }

    @Override
    public String getUsername() { return username; }
}
