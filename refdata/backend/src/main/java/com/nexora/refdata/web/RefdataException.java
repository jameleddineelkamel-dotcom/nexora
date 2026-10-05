package com.nexora.refdata.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Erreur métier renvoyée au client au format RFC 9457, avec la liste détaillée des erreurs. */
public class RefdataException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> erreurs;

    public RefdataException(HttpStatus status, List<String> erreurs) {
        super(String.join(" ", erreurs));
        this.status = status;
        this.erreurs = List.copyOf(erreurs);
    }

    public static RefdataException introuvable(String message) {
        return new RefdataException(HttpStatus.NOT_FOUND, List.of(message));
    }

    public static RefdataException invalide(List<String> erreurs) {
        return new RefdataException(HttpStatus.BAD_REQUEST, erreurs);
    }

    public static RefdataException invalide(String message) {
        return invalide(List.of(message));
    }

    public static RefdataException conflit(String message) {
        return new RefdataException(HttpStatus.CONFLICT, List.of(message));
    }

    public HttpStatus getStatus() { return status; }

    public List<String> getErreurs() { return erreurs; }
}
