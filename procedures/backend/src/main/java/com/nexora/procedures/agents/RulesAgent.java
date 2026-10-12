package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.*;
import com.nexora.procedures.studio.Modele.*;
import com.nexora.regles.MoteurRegles;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Single Window Agent — fonction « Rules » : traduit le tableau des frais et les pièces conditionnelles en règles DRL
 * (Drools), conserve les règles écrites à la main et vérifie que l'ensemble compile.
 */
@Component
public class RulesAgent implements Agent {

    @Override
    public String code() {
        return "REGLES";
    }

    @Override
    public Sortie executer(Contexte ctx) {
        VersionComplete v = ctx.version();
        String actuel = v.rules() == null || v.rules().drl() == null || v.rules().drl().isBlank() ? DrlGenerateur.entete(v.procedure().code()) : v.rules().drl();
        String drl = DrlGenerateur.remplacerSection(actuel, "pieces", DrlGenerateur.pieces(v.documents()));
        drl = DrlGenerateur.remplacerSection(drl, "frais", DrlGenerateur.frais(v.fees()));
        List<String> erreurs = MoteurRegles.verifier(drl);
        List<Proposition> props = new ArrayList<>();
        if (!drl.equals(actuel)) {
            long regles = drl.lines().filter(l -> l.startsWith("rule ")).count();
            props.add(new Proposition("DRL", v.procedure().code(), "Règles DRL : " + v.fees().size() + " ligne(s) de frais, "
                    + v.documents().stream().filter(d -> d.conditionText() != null && !d.conditionText().isBlank()).count() + " pièce(s) conditionnelle(s)",
                    Map.of("drl", drl, "errors", erreurs),
                    erreurs.isEmpty() ? "Le DRL régénéré compile sans erreur (" + regles + " règles). Les règles écrites à la main hors des sections générées sont conservées."
                            : "Attention : le DRL régénéré ne compile pas — " + String.join(" ; ", erreurs),
                    null, "generation", erreurs.isEmpty() ? 0.9 : 0.3));
        }
        // Règles de gestion en clair sans règle DRL associée : signalées pour écriture manuelle
        if (v.rules() != null)
            for (BusinessRule r : v.rules().businessRules())
                if (r.drlRule() == null || r.drlRule().isBlank() || !drl.contains("rule \"" + r.drlRule() + "\""))
                    props.add(new Proposition("FINDING", r.code(), "Règle de gestion " + r.code() + " sans règle DRL",
                            Map.of("severity", "warning", "rule", r.code(), "message", r.text()),
                            "La règle de gestion n'est reliée à aucune règle exécutable : à traduire en DRL ou à justifier (contrôle humain, autre service).",
                            r.text(), "check", 0.7));
        return Sortie.de(props.isEmpty() ? "Les règles DRL sont à jour et compilent." : "DRL régénéré" + (erreurs.isEmpty() ? " (compilation réussie)." : " — erreurs de compilation."), props);
    }
}
