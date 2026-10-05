package com.nexora.refdata.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

/** Erreurs au format RFC 9457 (application/problem+json) avec une propriété « erreurs ». */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RefdataException.class)
    ProblemDetail metier(RefdataException ex) {
        return probleme(ex.getStatus(), ex.getErreurs());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail doublon(DuplicateKeyException ex) {
        return probleme(HttpStatus.CONFLICT, List.of("Cet identifiant existe déjà dans le référentiel."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrite(DataIntegrityViolationException ex) {
        log.warn("Contrainte de base de données : {}", ex.getMostSpecificCause().getMessage());
        return probleme(HttpStatus.CONFLICT, List.of("La base de données a refusé l'opération : "
                + ex.getMostSpecificCause().getMessage().lines().findFirst().orElse("")));
    }

    /** Erreurs levées par les triggers (ex. suppression interdite d'un code). */
    @ExceptionHandler(UncategorizedSQLException.class)
    ProblemDetail sql(UncategorizedSQLException ex) {
        String msg = ex.getSQLException() != null ? ex.getSQLException().getMessage() : ex.getMessage();
        return probleme(HttpStatus.CONFLICT, List.of(msg.replaceFirst("^ERROR: |^ERREUR: ", "").lines().findFirst().orElse(msg)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail parametre(MethodArgumentTypeMismatchException ex) {
        return probleme(HttpStatus.BAD_REQUEST, List.of("Paramètre « " + ex.getName() + " » invalide : " + ex.getValue()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail illisible(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        return probleme(HttpStatus.BAD_REQUEST, List.of("Corps de requête JSON invalide : "
                + (cause == null ? "" : cause.lines().findFirst().orElse(""))));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail taille(MaxUploadSizeExceededException ex) {
        return probleme(HttpStatus.PAYLOAD_TOO_LARGE, List.of("Fichier trop volumineux (60 Mo maximum)."));
    }

    private static ProblemDetail probleme(HttpStatus statut, List<String> erreurs) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(statut, String.join(" ", erreurs));
        pd.setTitle(statut.getReasonPhrase());
        pd.setProperty("erreurs", erreurs);
        return pd;
    }
}
