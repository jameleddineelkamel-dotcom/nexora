package com.nexora.regles;

import java.math.BigDecimal;
import java.util.*;

/**
 * Fait Drools : la demande soumise, vue de façon générique (procédure, type, nature, champs du formulaire, groupes
 * répétitifs, pièces jointes). Les règles DRL de chaque procédure s'écrivent sur ce fait ; la même classe existe
 * dans le studio (test des règles) et dans le Runtime (exécution).
 */
public class Demande {

    private String procedure;
    private String typeDemande;
    private String nature;
    private String demandeur;
    private boolean mandataire;
    private boolean titulaire;
    private int anneeCourante;
    private Map<String, Object> champs = new LinkedHashMap<>();
    private Map<String, List<Map<String, Object>>> groupes = new LinkedHashMap<>();
    private Set<String> pieces = new LinkedHashSet<>();

    public Demande() {}

    public Demande(String procedure, String typeDemande, String nature, int anneeCourante) {
        this.procedure = procedure;
        this.typeDemande = typeDemande;
        this.nature = nature;
        this.anneeCourante = anneeCourante;
    }

    /** Valeur brute d'un champ (null si absent). */
    public Object champ(String code) {
        return champs.get(code);
    }

    /** Valeur textuelle d'un champ, chaîne vide si absent : utilisable directement dans les contraintes DRL. */
    public String texte(String code) {
        Object v = champs.get(code);
        return v == null ? "" : String.valueOf(v).strip();
    }

    public boolean renseigne(String code) {
        return !texte(code).isEmpty();
    }

    public BigDecimal nombre(String code) {
        String t = texte(code).replace(" ", "").replace(',', '.');
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Valeur entière d'un champ, ou 0 si absente ou invalide. */
    public int entier(String code) {
        BigDecimal n = nombre(code);
        return n == null ? 0 : n.intValue();
    }

    public boolean aPiece(String code) {
        return pieces.contains(code);
    }

    public int lignes(String groupe) {
        List<Map<String, Object>> l = groupes.get(groupe);
        return l == null ? 0 : l.size();
    }

    public List<Map<String, Object>> groupe(String code) {
        return groupes.getOrDefault(code, List.of());
    }

    public String getProcedure() { return procedure; }
    public void setProcedure(String procedure) { this.procedure = procedure; }
    public String getTypeDemande() { return typeDemande; }
    public void setTypeDemande(String typeDemande) { this.typeDemande = typeDemande; }
    public String getNature() { return nature; }
    public void setNature(String nature) { this.nature = nature; }
    public String getDemandeur() { return demandeur; }
    public void setDemandeur(String demandeur) { this.demandeur = demandeur; }
    public boolean isMandataire() { return mandataire; }
    public void setMandataire(boolean mandataire) { this.mandataire = mandataire; }
    public boolean isTitulaire() { return titulaire; }
    public void setTitulaire(boolean titulaire) { this.titulaire = titulaire; }
    public int getAnneeCourante() { return anneeCourante; }
    public void setAnneeCourante(int anneeCourante) { this.anneeCourante = anneeCourante; }
    public Map<String, Object> getChamps() { return champs; }
    public void setChamps(Map<String, Object> champs) { this.champs = champs == null ? new LinkedHashMap<>() : champs; }
    public Map<String, List<Map<String, Object>>> getGroupes() { return groupes; }
    public void setGroupes(Map<String, List<Map<String, Object>>> groupes) { this.groupes = groupes == null ? new LinkedHashMap<>() : groupes; }
    public Set<String> getPieces() { return pieces; }
    public void setPieces(Set<String> pieces) { this.pieces = pieces == null ? new LinkedHashSet<>() : pieces; }
}
