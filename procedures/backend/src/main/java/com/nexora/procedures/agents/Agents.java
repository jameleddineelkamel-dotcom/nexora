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
    public record Contexte(VersionComplete version, String objectif, String texteSource, String nomSource, List<Diagramme> diagrammes) {
        public Contexte(VersionComplete version, String objectif, String texteSource, String nomSource) {
            this(version, objectif, texteSource, nomSource, List.of());
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
