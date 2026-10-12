package com.nexora.regles;

import org.kie.api.KieBase;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exécution des règles DRL (Drools) d'une procédure. Le DRL est compilé une fois par empreinte puis mis en cache.
 * Chaque point d'évaluation du workflow est un agenda-group DRL (« validation », « frais »…) : seul le groupe
 * demandé est évalué. Le résultat est collecté dans le global {@code resultat}.
 */
public final class MoteurRegles {

    public static final List<String> POINTS = List.of("validation", "frais", "decision");

    private static final ConcurrentHashMap<String, KieBase> CACHE = new ConcurrentHashMap<>();

    private MoteurRegles() {}

    /** Erreurs de compilation (liste vide si le DRL est valide). */
    public static List<String> verifier(String drl) {
        Results r = new KieHelper().addContent(drl, ResourceType.DRL).verify();
        return r.getMessages(Message.Level.ERROR).stream()
                .map(m -> "Ligne " + m.getLine() + " : " + m.getText().strip()).toList();
    }

    public static Resultat evaluer(String cle, String drl, String point, Demande demande, Collection<Antecedent> antecedents) {
        KieBase base = CACHE.computeIfAbsent(cle, k -> {
            List<String> erreurs = verifier(drl);
            if (!erreurs.isEmpty()) throw new IllegalStateException("Règles DRL invalides : " + String.join(" ; ", erreurs));
            return new KieHelper().addContent(drl, ResourceType.DRL).build();
        });
        Resultat resultat = new Resultat();
        KieSession s = base.newKieSession();
        try {
            if (base.getKiePackages().stream().anyMatch(p -> p.getGlobalVariables().stream().anyMatch(g -> g.getName().equals("resultat"))))
                s.setGlobal("resultat", resultat);
            s.addEventListener(new DefaultAgendaEventListener() {
                @Override
                public void afterMatchFired(AfterMatchFiredEvent e) {
                    resultat.declenchee(e.getMatch().getRule().getName());
                }
            });
            s.insert(demande);
            if (antecedents != null) antecedents.forEach(s::insert);
            s.getAgenda().getAgendaGroup(point).setFocus();
            s.fireAllRules();
        } finally {
            s.dispose();
        }
        return resultat;
    }
}
