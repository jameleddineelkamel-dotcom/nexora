package com.nexora.eservices.registre;

import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.web.EservicesException;
import com.nexora.eservices.web.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.*;

/**
 * Appels aux autres microservices de la plateforme avec le jeton SSO de l'utilisateur courant : studio des procédures
 * (paquets publiés) et registre des opérateurs (pré-remplissage par NIU, pièces d'enrôlement, mise à jour RG2 UC-05).
 */
@Component
public class Services {

    private static final Logger log = LoggerFactory.getLogger(Services.class);

    public record Partie(String niu, String legalName, String relation) {}

    public record PieceRegistre(long id, String docCode, String fileName, String mimeType, long sizeBytes) {}

    public record Code(String code, String label, String parentCode) {}

    private final RestClient procedures;
    private final RestClient registre;
    private final RestClient refdata;
    private final Json json;

    public Services(@Value("${nexora.procedures.url}") String proceduresUrl, @Value("${nexora.registration.url}") String registreUrl,
                    @Value("${nexora.refdata.url}") String refdataUrl, Json json) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(20));
        this.procedures = RestClient.builder().baseUrl(proceduresUrl).requestFactory(f).build();
        this.registre = RestClient.builder().baseUrl(registreUrl).requestFactory(f).build();
        this.refdata = RestClient.builder().baseUrl(refdataUrl).requestFactory(f).build();
        this.json = json;
    }

    private static String bearer() {
        return "Bearer " + Habilitations.jeton().orElseThrow(() -> EservicesException.interdit("Appel inter-services sans jeton utilisateur."));
    }

    // ------------------------------------------------------------------ studio des procédures
    public List<Map<String, Object>> publiees() {
        try {
            String r = procedures.get().uri("/api/v1/published").header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(String.class);
            return json.mapper().readValue(r, new TypeReference<>() {});
        } catch (HttpClientErrorException.Forbidden e) {
            throw EservicesException.interdit("Le droit « procedures.lire » est nécessaire pour consulter les procédures publiées du studio.");
        } catch (RestClientException e) {
            throw EservicesException.indisponible("Le studio des procédures ne répond pas : " + e.getMessage());
        }
    }

    /** Code Angular généré par le studio pour une version publiée en rendu GENERE : { format, component, files: [{ path, content }] }. */
    public Map<String, Object> codeAngular(String code, int versionNo) {
        try {
            String r = procedures.get().uri("/api/v1/angular/{c}/{n}", code, versionNo).header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(String.class);
            return json.mapper().readValue(r, new TypeReference<>() {});
        } catch (HttpClientErrorException e) {
            throw EservicesException.invalide("Code Angular de " + code + " v" + versionNo + " indisponible dans le studio : " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw EservicesException.indisponible("Le studio des procédures ne répond pas : " + e.getMessage());
        }
    }

    /** Paquet publié et son empreinte annoncée par le studio. */
    public Map.Entry<String, String> paquetPublie(String code) {
        try {
            ResponseEntity<String> r = procedures.get().uri("/api/v1/published/{c}", code).header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve().toEntity(String.class);
            return Map.entry(Objects.requireNonNull(r.getBody()), Objects.toString(r.getHeaders().getFirst("X-Nexora-Package-Hash"), ""));
        } catch (HttpClientErrorException.NotFound e) {
            throw EservicesException.introuvable("Aucune version publiée de la procédure " + code + " dans le studio.");
        } catch (RestClientException e) {
            throw EservicesException.indisponible("Le studio des procédures ne répond pas : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ référentiel commun
    /** Liste de codes d'une table REF_* dans une langue (fr, en), valide à la date du jour. */
    public List<Code> listeDeCodes(String table, String langue) {
        try {
            String r = refdata.get().uri("/api/v1/lookup/{t}?lang={l}&limit=5000", table, langue).header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve().body(String.class);
            return json.mapper().readValue(r, new TypeReference<>() {});
        } catch (HttpClientErrorException.Forbidden e) {
            throw EservicesException.interdit("Le droit « refdata.lire » est nécessaire pour charger les listes du Référentiel Commun.");
        } catch (HttpClientErrorException.NotFound e) {
            throw EservicesException.introuvable("Table inconnue du Référentiel Commun : " + table);
        } catch (RestClientException e) {
            throw EservicesException.indisponible("Le Référentiel Commun ne répond pas : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ registre des opérateurs
    /** Opérateurs rattachés à l'utilisateur (titulaire ou mandataire) ; liste vide si le registre est indisponible. */
    public List<Partie> mesParties() {
        if (Habilitations.jeton().isEmpty()) return List.of();
        try {
            String r = registre.get().uri("/api/v1/me/parties").header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(String.class);
            return json.mapper().readValue(r, new TypeReference<>() {});
        } catch (RestClientException e) {
            log.warn("Registre des opérateurs indisponible : {}", e.getMessage());
            return List.of();
        }
    }

    /** Fiche d'un opérateur (pré-remplissage) ; vide s'il est inconnu. */
    public Optional<Map<String, Object>> partie(String niu) {
        try {
            String r = registre.get().uri("/api/v1/parties/{n}", niu).header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(String.class);
            return Optional.of(json.map(r));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException.Forbidden e) {
            throw EservicesException.interdit("Vous n'êtes pas autorisé à consulter la fiche de l'opérateur " + niu + ".");
        } catch (RestClientException e) {
            throw EservicesException.indisponible("Le registre des opérateurs ne répond pas : " + e.getMessage());
        }
    }

    public List<PieceRegistre> piecesPartie(String niu) {
        try {
            String r = registre.get().uri("/api/v1/parties/{n}/documents", niu).header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(String.class);
            return json.mapper().readValue(r, new TypeReference<>() {});
        } catch (RestClientException e) {
            log.warn("Pièces du registre indisponibles pour {} : {}", niu, e.getMessage());
            return List.of();
        }
    }

    public byte[] contenuPiece(String niu, long id) {
        return registre.get().uri("/api/v1/parties/{n}/documents/{i}/content", niu, id).header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve().body(byte[].class);
    }

    /** Complète les données manquantes de l'opérateur (RG2 UC-05) ; renvoie les attributs effectivement complétés. */
    public Map<String, Object> completer(String niu, Map<String, Object> attributs) {
        String r = registre.post().uri("/api/v1/parties/{n}/complete", niu).header(HttpHeaders.AUTHORIZATION, bearer())
                .header(HttpHeaders.CONTENT_TYPE, "application/json").body(json.ecrire(attributs)).retrieve().body(String.class);
        return json.map(r);
    }
}
