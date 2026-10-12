package com.nexora.regles;

import java.math.BigDecimal;
import java.util.*;

/**
 * Résultat d'une évaluation de règles (global DRL {@code resultat}) : erreurs bloquantes, lignes de frais par
 * bénéficiaire, variables transmises au workflow et règles déclenchées (traçabilité).
 */
public class Resultat {

    public record Erreur(String champ, String message) {}

    public record Frais(String code, String beneficiaire, String libelle, BigDecimal montant) {}

    private final List<Erreur> erreurs = new ArrayList<>();
    private final List<Frais> frais = new ArrayList<>();
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final List<String> reglesDeclenchees = new ArrayList<>();

    public void erreur(String champ, String message) {
        erreurs.add(new Erreur(champ, message));
    }

    public void frais(String code, String beneficiaire, String libelle, Number montant) {
        frais.add(new Frais(code, beneficiaire, libelle, new BigDecimal(String.valueOf(montant))));
    }

    public void variable(String nom, Object valeur) {
        variables.put(nom, valeur);
    }

    public void declenchee(String regle) {
        reglesDeclenchees.add(regle);
    }

    public BigDecimal total() {
        return frais.stream().map(Frais::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean valide() {
        return erreurs.isEmpty();
    }

    public List<Erreur> getErreurs() { return erreurs; }
    public List<Frais> getFrais() { return frais; }
    public Map<String, Object> getVariables() { return variables; }
    public List<String> getReglesDeclenchees() { return reglesDeclenchees; }
}
