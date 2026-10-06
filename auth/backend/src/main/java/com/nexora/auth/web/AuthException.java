package com.nexora.auth.web;

import org.springframework.http.HttpStatus;

import java.util.List;

public class AuthException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> erreurs;

    public AuthException(HttpStatus status, List<String> erreurs) {
        super(String.join(" ", erreurs));
        this.status = status;
        this.erreurs = List.copyOf(erreurs);
    }

    public static AuthException invalide(String... m) { return new AuthException(HttpStatus.BAD_REQUEST, List.of(m)); }
    public static AuthException invalide(List<String> m) { return new AuthException(HttpStatus.BAD_REQUEST, m); }
    public static AuthException introuvable(String m) { return new AuthException(HttpStatus.NOT_FOUND, List.of(m)); }
    public static AuthException conflit(String m) { return new AuthException(HttpStatus.CONFLICT, List.of(m)); }
    public static AuthException refuse(String m) { return new AuthException(HttpStatus.UNAUTHORIZED, List.of(m)); }

    public HttpStatus getStatus() { return status; }
    public List<String> getErreurs() { return erreurs; }
}
