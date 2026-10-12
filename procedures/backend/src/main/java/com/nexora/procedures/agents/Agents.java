package com.nexora.procedures.agents;

import com.nexora.procedures.studio.Modele.VersionComplete;

import java.util.List;
import java.util.Map;

/** Contrats communs aux agents du studio. */
public final class Agents {

    private Agents() {}

    /** Proposition structurée et sourcée d'un agent ; elle n'est appliquée qu'après revue humaine. */
    public record Proposition(String kind, String target, String title, Map<String, Object> payload, String rationale, String sourceExcerpt,
                              String type, double confidence) {}

    /** Contexte d'une exécution : version visée, objectif, texte source éventuel (SFD) et diagrammes joints (.drawio, .bpmn). */
    public record Contexte(VersionComplete version, String objectif, String texteSource, String nomSource, List<Diagramme> diagrammes,
                           List<Annexe> annexes) {
        public Contexte(VersionComplete version, String objectif, String texteSource, String nomSource) {
            this(version, objectif, texteSource, nomSource, List.of(), List.of());
        }

        public Contexte(VersionComplete version, String objectif, String texteSource, String nomSource, List<Diagramme> diagrammes) {
            this(version, objectif, texteSource, nomSource, diagrammes, List.of());
        }
    }

    /**
     * Annexe de la SFD : modèle de données (classeur .xlsx), maquettes d'écrans (.html, export Figma .json) ou image
     * (PNG, JPEG : figure de la SFD, capture d'écran) lue par le modèle de vision.
     */
    public record Annexe(String nom, byte[] contenu) {
        public boolean classeur() {
            return ModeleDonnees.estClasseur(nom, contenu);
        }

        /** PNG ou JPEG (d'après la signature du fichier). */
        public boolean image() {
            return contenu.length > 8 && ((contenu[0] & 0xFF) == 0x89 && contenu[1] == 'P' && contenu[2] == 'N' && contenu[3] == 'G'
                    || (contenu[0] & 0xFF) == 0xFF && (contenu[1] & 0xFF) == 0xD8);
        }

        public boolean figma() {
            return FigmaSource.estFigma(nom, texte());
        }

        public String texte() {
            return new String(contenu, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Diagramme joint à une exécution : source draw.io ou BPMN 2.0 (souvent celle des figures de la SFD). */
    public record Diagramme(String nom, String contenu) {
        public boolean bpmn() {
            return contenu.contains("http://www.omg.org/spec/BPMN/20100524/MODEL");
        }
    }

    public record Sortie(String resume, List<Proposition> propositions, String modele) {
        public static Sortie de(String resume, List<Proposition> propositions) {
            return new Sortie(resume, propositions, "heuristique");
        }
    }

    /** Un agent métier ou technique : il propose, il n'applique jamais lui-même (politique d'autonomie). */
    public interface Agent {
        String code();

        Sortie executer(Contexte contexte);
    }
}
