package com.nexora.procedures.agents;

import com.nexora.procedures.studio.VersionService;
import com.nexora.procedures.web.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modèle de langage auto-hébergé des agents (souveraineté, AP-08) : n'importe quel serveur exposant l'API compatible
 * OpenAI {@code POST /v1/chat/completions} — Ollama, vLLM, LM Studio, llama.cpp, TGI. Aucune donnée ne quitte
 * l'infrastructure choisie. Sans URL ni modèle configurés, ou si le serveur ne répond pas, les agents restent en mode
 * heuristique déterministe. Le modèle ne fait que proposer : ses réponses sont validées par du code (citation vérifiée
 * dans le document, choix restreint à des candidats connus) puis passent par la revue humaine.
 */
@Component
public class ModeleLangage {

    private static final Logger log = LoggerFactory.getLogger(ModeleLangage.class);

    /** Réponse JSON du modèle, avec son empreinte (traçabilité) et sa durée. */
    public record Reponse(Map<String, Object> json, String sha256, long millis) {}

    /** Bilan des appels d'une exécution d'agent (journalisé dans ai_run). */
    public static final class Bilan {
        private final AtomicInteger appels = new AtomicInteger(), echecs = new AtomicInteger();
        private final AtomicLong millis = new AtomicLong();
        private final StringBuilder empreintes = new StringBuilder();

        synchronized void ajouter(Reponse r) {
            appels.incrementAndGet();
            millis.addAndGet(r.millis());
            empreintes.append(r.sha256());
        }

        void echec() {
            appels.incrementAndGet();
            echecs.incrementAndGet();
        }

        public int appels() { return appels.get(); }

        public int echecs() { return echecs.get(); }

        public long millis() { return millis.get(); }

        /** Empreinte globale des réponses reçues (null si aucune). */
        public synchronized String empreinte() {
            return empreintes.isEmpty() ? null : VersionService.sha256(empreintes.toString());
        }
    }

    private static final ThreadLocal<Bilan> BILAN = new ThreadLocal<>();

    private final String url;
    private final String modele;
    /** Modèle capable de lire des images (diagrammes et écrans en PNG / JPEG) ; vide = pas de lecture d'image. */
    private final String modeleVision;
    private final String cle;
    private final Duration delai;
    private final int maxCaracteres;
    private final Json json;
    private final HttpClient http;
    private volatile boolean formatJson = true;

    public ModeleLangage(@Value("${nexora.ai.url:}") String url, @Value("${nexora.ai.model:}") String modele,
                         @Value("${nexora.ai.api-key:}") String cle, @Value("${nexora.ai.timeout:PT300S}") Duration delai,
                         @Value("${nexora.ai.max-chars:20000}") int maxCaracteres, @Value("${nexora.ai.vision-model:}") String modeleVision, Json json) {
        String u = url == null ? "" : url.strip();
        this.url = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.modele = modele == null ? "" : modele.strip();
        this.modeleVision = modeleVision == null ? "" : modeleVision.strip();
        this.cle = cle == null ? "" : cle.strip();
        this.delai = delai;
        this.maxCaracteres = Math.max(2000, maxCaracteres);
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Un serveur et un modèle sont configurés. */
    public boolean disponible() {
        return !url.isEmpty() && !modele.isEmpty();
    }

    public String nom() {
        return disponible() ? modele : "heuristique";
    }

    /** Un modèle de vision est configuré (lecture des images jointes). */
    public boolean visionDisponible() {
        return !url.isEmpty() && !modeleVision.isEmpty();
    }

    public String nomVision() {
        return modeleVision;
    }

    public int maxCaracteres() {
        return maxCaracteres;
    }

    /** État pour l'interface : configuration et joignabilité du serveur (GET /models). */
    public Map<String, Object> etat() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("available", disponible());
        e.put("model", nom());
        e.put("provider", disponible() || visionDisponible() ? "auto-hébergé (API compatible OpenAI)" : "aucun");
        e.put("visionModel", visionDisponible() ? modeleVision : null);
        if (disponible()) {
            e.put("endpoint", url);
            try {
                HttpResponse<String> r = http.send(requete("/models").timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                e.put("reachable", r.statusCode() / 100 == 2);
                e.put("modelInstalled", r.statusCode() / 100 == 2 && r.body().contains("\"" + modele + "\""));
            } catch (Exception x) {
                e.put("reachable", false);
                e.put("modelInstalled", false);
            }
        }
        return e;
    }

    /** Ouvre le bilan des appels de l'exécution courante (un agent = un fil). */
    public Bilan suivre() {
        Bilan b = new Bilan();
        BILAN.set(b);
        return b;
    }

    public void terminer() {
        BILAN.remove();
    }

    /**
     * Demande au modèle une réponse JSON. {@code systeme} fixe le rôle et le schéma ; {@code utilisateur} porte les
     * données. Renvoie vide en cas d'indisponibilité, d'erreur ou de réponse non JSON : l'agent continue sans le modèle.
     */
    public Optional<Reponse> json(String systeme, String utilisateur) {
        if (!disponible()) return Optional.empty();
        return demander(modele, systeme, utilisateur);
    }

    /** Lecture d'une image (PNG, JPEG) par le modèle de vision : réponse JSON selon le schéma de {@code systeme}. */
    public Optional<Reponse> jsonImage(String systeme, String consigne, byte[] image, String type) {
        if (!visionDisponible()) return Optional.empty();
        String url = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(image);
        return demander(modeleVision, systeme, List.of(Map.of("type", "text", "text", consigne), Map.of("type", "image_url", "image_url", Map.of("url", url))));
    }

    private Optional<Reponse> demander(String nomModele, String systeme, Object utilisateur) {
        Bilan bilan = BILAN.get();
        long debut = System.currentTimeMillis();
        try {
            String contenu = appeler(nomModele, systeme, utilisateur);
            Map<String, Object> objet = json.map(extraireObjet(contenu));
            Reponse r = new Reponse(objet, VersionService.sha256(contenu), System.currentTimeMillis() - debut);
            if (bilan != null) bilan.ajouter(r);
            return Optional.of(r);
        } catch (Exception e) {
            log.warn("Modèle de langage {} : {}", nomModele, e.getMessage());
            if (bilan != null) bilan.echec();
            return Optional.empty();
        }
    }

    private String appeler(String nomModele, String systeme, Object utilisateur) throws Exception {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("model", nomModele);
        corps.put("temperature", 0);
        corps.put("stream", false);
        corps.put("messages", List.of(Map.of("role", "system", "content", systeme), Map.of("role", "user", "content", utilisateur)));
        if (formatJson) corps.put("response_format", Map.of("type", "json_object"));
        HttpResponse<String> r = http.send(requete("/chat/completions").timeout(delai).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.ecrire(corps))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 400 && formatJson) {
            // Serveur sans « response_format » : le schéma reste imposé par la consigne
            formatJson = false;
            return appeler(nomModele, systeme, utilisateur);
        }
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + r.statusCode() + " " + r.body().substring(0, Math.min(200, r.body().length())));
        Map<String, Object> reponse = json.map(r.body());
        Object choix = reponse.get("choices");
        if (!(choix instanceof List<?> l) || l.isEmpty() || !(l.getFirst() instanceof Map<?, ?> c) || !(c.get("message") instanceof Map<?, ?> m))
            throw new IllegalStateException("réponse sans « choices[0].message »");
        return Objects.toString(m.get("content"), "");
    }

    private HttpRequest.Builder requete(String chemin) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + chemin));
        if (!cle.isEmpty()) b.header("Authorization", "Bearer " + cle);
        return b;
    }

    /** Objet JSON de la réponse, sans bloc de code Markdown ni texte autour. */
    static String extraireObjet(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b < a) throw new IllegalStateException("réponse non JSON");
        return s.substring(a, b + 1);
    }

    /** Découpe un texte long en morceaux d'au plus {@code max} caractères, aux fins de ligne. */
    public static List<String> decouper(String texte, int max) {
        List<String> r = new ArrayList<>();
        StringBuilder courant = new StringBuilder();
        for (String ligne : texte.split("\n")) {
            if (courant.length() + ligne.length() + 1 > max && !courant.isEmpty()) {
                r.add(courant.toString());
                courant.setLength(0);
            }
            courant.append(ligne.length() > max ? ligne.substring(0, max) : ligne).append('\n');
        }
        if (!courant.toString().isBlank()) r.add(courant.toString());
        return r;
    }
}
