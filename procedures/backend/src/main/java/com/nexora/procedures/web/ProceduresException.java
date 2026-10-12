package com.nexora.procedures.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Erreur métier renvoyée au client au format RFC 9457, avec la liste détaillée des erreurs. */
public class ProceduresException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> erreurs;

    public ProceduresException(HttpStatus status, List<String> erreurs) {
        super(String.join(" ", erreurs));
        this.status = status;
        this.erreurs = List.copyOf(erreurs);
    }

    public static ProceduresException introuvable(String message) {
        return new ProceduresException(HttpStatus.NOT_FOUND, List.of(message));
    }

    public static ProceduresException invalide(List<String> erreurs) {
        return new ProceduresException(HttpStatus.BAD_REQUEST, erreurs);
    }

    public static ProceduresException invalide(String message) {
        return invalide(List.of(message));
    }

    public static ProceduresException conflit(String message) {
        return new ProceduresException(HttpStatus.CONFLICT, List.of(message));
    }

    public HttpStatus getStatus() { return status; }

    public List<String> getErreurs() { return erreurs; }
}
