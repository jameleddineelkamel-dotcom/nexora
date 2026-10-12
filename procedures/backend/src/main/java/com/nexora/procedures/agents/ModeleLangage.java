package com.nexora.procedures.agents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Modèle de langage optionnel des agents (souveraineté configurable, AP-08). Sans clé d'API, les agents fonctionnent
 * en mode heuristique déterministe ; la base relationnelle reste la vérité et toute proposition passe par une revue.
 */
@Component
public class ModeleLangage {

    private final String cle;
    private final String modele;

    public ModeleLangage(@Value("${nexora.ai.api-key:}") String cle, @Value("${nexora.ai.model:}") String modele) {
        this.cle = cle == null ? "" : cle.strip();
        this.modele = modele == null ? "" : modele.strip();
    }

    public boolean disponible() {
        return !cle.isEmpty() && !modele.isEmpty();
    }

    public String nom() {
        return disponible() ? modele : "heuristique";
    }
}
