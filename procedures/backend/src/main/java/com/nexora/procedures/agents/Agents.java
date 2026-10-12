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

    /** Contexte d'une exécution : version visée, objectif, texte source éventuel (SFD), modèle de langage disponible. */
    public record Contexte(VersionComplete version, String objectif, String texteSource, String nomSource) {}

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
