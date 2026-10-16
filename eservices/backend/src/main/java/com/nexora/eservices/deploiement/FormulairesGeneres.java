package com.nexora.eservices.deploiement;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.registre.Services;
import com.nexora.eservices.web.EservicesException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Formulaires en code Angular généré (procédures dont le réglage « rendu » vaut GENERE) : l'action « Préparer le code
 * généré » récupère auprès du studio le composant de chaque version déployée concernée, l'écrit dans le dossier des
 * sources d'e-Services ({@code nexora.formulaires-generes.dossier}) et réécrit le registre {@code index.ts}. Il faut
 * ensuite reconstruire et redéployer e-Services ; sans composant compilé, une version garde le rendu dynamique.
 * Droit d'administration (règle /api/v1/admin/**).
 */
@RestController
@RequestMapping("/api/v1/admin/generated-forms")
public class FormulairesGeneres {

    private static final Pattern NOM = Pattern.compile("^[a-z0-9-]+-v\\d+\\.(ts|html)$");
    private static final Pattern ENTETE = Pattern.compile("^// Code généré par le studio NEXORA .*— procédure ([A-Za-z0-9_]+), version (\\d+)");
    private static final Pattern CLASSE = Pattern.compile("export class ([A-Za-z0-9_]+) extends FormulaireGenereBase");

    private final DeploiementService deploiements;
    private final Services services;
    private final Journal journal;
    private final Path dossier;

    public FormulairesGeneres(DeploiementService deploiements, Services services, Journal journal,
                              @Value("${nexora.formulaires-generes.dossier:frontend/src/app/formulaires-generes}") String dossier) {
        this.deploiements = deploiements;
        this.services = services;
        this.journal = journal;
        this.dossier = Path.of(dossier).toAbsolutePath().normalize();
    }

    /** Déploiements, rendu demandé par la procédure et présence du code dans les sources d'e-Services. */
    @GetMapping
    public Map<String, Object> etat() {
        Set<String> presents = presents().keySet();
        List<Map<String, Object>> l = new ArrayList<>();
        for (DeploiementService.Deploiement d : deploiements.deploiements()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("procedure", d.procedureCode());
            m.put("versionNo", d.versionNo());
            m.put("labelFr", d.labelFr());
            m.put("active", d.active());
            m.put("rendu", rendu(d));
            m.put("inSources", presents.contains(cle(d.procedureCode(), d.versionNo())));
            l.add(m);
        }
        return Map.of("directory", dossier.toString(), "directoryExists", Files.isDirectory(dossier), "deployments", l);
    }

    /** Récupère le code des versions déployées en rendu GENERE et réécrit le registre. */
    @PostMapping
    public Map<String, Object> preparer() {
        if (!Files.isDirectory(dossier))
            throw EservicesException.invalide("Dossier des formulaires générés introuvable : " + dossier
                    + ". Cette action se lance sur un poste ou un serveur de construction qui dispose des sources d'e-Services (réglage nexora.formulaires-generes.dossier).");
        List<String> ecrits = new ArrayList<>();
        for (DeploiementService.Deploiement d : deploiements.deploiements()) {
            if (!"GENERE".equals(rendu(d))) continue;
            Map<String, Object> code = services.codeAngular(d.procedureCode(), d.versionNo());
            if (!(code.get("files") instanceof List<?> fichiers)) throw EservicesException.invalide("Réponse du studio sans fichiers pour " + d.procedureCode() + ".");
            for (Object o : fichiers) {
                Map<?, ?> f = o instanceof Map<?, ?> x ? x : Map.of();
                String nom = String.valueOf(f.get("path")), contenu = String.valueOf(f.get("content"));
                // Nom contrôlé : aucun chemin ni fichier hors du dossier des formulaires générés
                if (!NOM.matcher(nom).matches()) throw EservicesException.invalide("Nom de fichier refusé dans la réponse du studio : " + nom);
                ecrire(dossier.resolve(nom), contenu);
                ecrits.add(nom);
            }
        }
        Map<String, String> registre = presents();
        ecrire(dossier.resolve("index.ts"), index(registre));
        journal.tracer("FORMULAIRES_GENERES_PREPARES", null, null, Map.of("files", ecrits, "registry", registre.keySet()));
        Map<String, Object> m = new LinkedHashMap<>(etat());
        m.put("written", ecrits);
        m.put("registry", registre.keySet());
        m.put("next", "Reconstruire puis redéployer e-Services pour compiler ces formulaires (sinon le rendu dynamique reste utilisé).");
        return m;
    }

    /** Rendu demandé par la version déployée (réglage « rendu » de son paquet publié). */
    private String rendu(DeploiementService.Deploiement d) {
        return "GENERE".equals(deploiements.paquet(d.id()).reglage("rendu", "")) ? "GENERE" : "DYNAMIQUE";
    }

    private static String cle(String procedure, int versionNo) {
        return procedure + "@" + versionNo;
    }

    /** Composants générés présents dans le dossier : clé « CODE@version » → fichier sans extension et classe. */
    private Map<String, String> presents() {
        Map<String, String> r = new TreeMap<>();
        if (!Files.isDirectory(dossier)) return r;
        try (Stream<Path> l = Files.list(dossier)) {
            for (Path p : l.filter(x -> NOM.matcher(x.getFileName().toString()).matches() && x.toString().endsWith(".ts")).sorted().toList()) {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                Matcher e = ENTETE.matcher(s), c = CLASSE.matcher(s);
                if (e.find() && c.find())
                    r.put(cle(e.group(1), Integer.parseInt(e.group(2))), p.getFileName().toString().replace(".ts", "") + "#" + c.group(1));
            }
        } catch (IOException e) {
            throw EservicesException.indisponible("Lecture du dossier des formulaires générés impossible : " + e.getMessage());
        }
        return r;
    }

    private static String index(Map<String, String> registre) {
        StringBuilder b = new StringBuilder();
        b.append("// Registre des formulaires générés en code Angular par le studio, réécrit par l'action d'administration\n");
        b.append("// « Préparer le code généré » (POST /api/v1/admin/generated-forms). Clé : « CODE_PROCEDURE@numéro de version ».\n");
        b.append("// Chaque composant est chargé à la demande (bundle séparé). Ne pas modifier à la main.\n");
        b.append("import { Type } from '@angular/core';\n\n");
        b.append("export const FORMULAIRES_GENERES: Record<string, () => Promise<Type<unknown>>> = {\n");
        registre.forEach((k, v) -> {
            String[] fc = v.split("#");
            b.append("  '").append(k).append("': () => import('./").append(fc[0]).append("').then(m => m.").append(fc[1]).append("),\n");
        });
        b.append("};\n");
        return b.toString();
    }

    private static void ecrire(Path p, String contenu) {
        try {
            Files.writeString(p, contenu, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw EservicesException.indisponible("Écriture impossible : " + p + " (" + e.getMessage() + ").");
        }
    }
}
