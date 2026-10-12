package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.*;
import com.nexora.procedures.studio.Modele.Constat;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compliance Agent (transversal) : contrôle la version et remet un rapport de conformité à l'humain qui décide. */
@Component
public class ComplianceAgent implements Agent {

    private final Conformite conformite;

    public ComplianceAgent(Conformite conformite) {
        this.conformite = conformite;
    }

    @Override
    public String code() {
        return "CONFORMITE";
    }

    @Override
    public Sortie executer(Contexte ctx) {
        List<Constat> constats = conformite.controler(ctx.version());
        long erreurs = constats.stream().filter(c -> c.severity().equals("error")).count();
        long alertes = constats.stream().filter(c -> c.severity().equals("warning")).count();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findings", constats);
        payload.put("errors", erreurs);
        payload.put("warnings", alertes);
        String resume = erreurs == 0 ? "Version conforme : " + alertes + " alerte(s), aucune erreur bloquante."
                : erreurs + " erreur(s) bloquante(s) et " + alertes + " alerte(s) : la soumission est refusée tant qu'elles subsistent.";
        return Sortie.de(resume, List.of(new Proposition("REPORT", ctx.version().procedure().code(), "Rapport de conformité", payload, resume,
                null, "check", erreurs == 0 ? 0.95 : 0.6)));
    }
}
