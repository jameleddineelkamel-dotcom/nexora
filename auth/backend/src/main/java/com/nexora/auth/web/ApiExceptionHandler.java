package com.nexora.auth.web;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/** Erreurs au format RFC 9457 avec la propriété « erreurs » (même convention que les autres microservices). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthException.class)
    ProblemDetail metier(AuthException ex) {
        return probleme(ex.getStatus(), ex.getErreurs());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail doublon(DuplicateKeyException ex) {
        return probleme(HttpStatus.CONFLICT, List.of("Cet identifiant existe déjà."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail illisible(HttpMessageNotReadableException ex) {
        return probleme(HttpStatus.BAD_REQUEST, List.of("Corps de requête JSON invalide."));
    }

    private static ProblemDetail probleme(HttpStatus statut, List<String> erreurs) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(statut, String.join(" ", erreurs));
        pd.setTitle(statut.getReasonPhrase());
        pd.setProperty("erreurs", erreurs);
        return pd;
    }
}
