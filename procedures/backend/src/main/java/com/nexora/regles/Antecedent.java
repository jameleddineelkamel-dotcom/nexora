package com.nexora.regles;

import java.time.LocalDate;

/**
 * Fait Drools : un dossier antérieur du même demandeur pour la même procédure (renouvellement, amendement,
 * contrôle des doublons). {@code titreValide} indique un livrable délivré et non expiré.
 */
public class Antecedent {

    private String dossierNo;
    private String typeDemande;
    private String nature;
    private String statut;
    private int annee;
    private LocalDate expiration;
    private boolean titreValide;
    private boolean enCours;

    public Antecedent() {}

    public Antecedent(String dossierNo, String typeDemande, String nature, String statut, int annee, LocalDate expiration,
                      boolean titreValide, boolean enCours) {
        this.dossierNo = dossierNo;
        this.typeDemande = typeDemande;
        this.nature = nature;
        this.statut = statut;
        this.annee = annee;
        this.expiration = expiration;
        this.titreValide = titreValide;
        this.enCours = enCours;
    }

    public String getDossierNo() { return dossierNo; }
    public void setDossierNo(String dossierNo) { this.dossierNo = dossierNo; }
    public String getTypeDemande() { return typeDemande; }
    public void setTypeDemande(String typeDemande) { this.typeDemande = typeDemande; }
    public String getNature() { return nature; }
    public void setNature(String nature) { this.nature = nature; }
    public String getStatut() { return statut; }
    public void setStatut(String statut) { this.statut = statut; }
    public int getAnnee() { return annee; }
    public void setAnnee(int annee) { this.annee = annee; }
    public LocalDate getExpiration() { return expiration; }
    public void setExpiration(LocalDate expiration) { this.expiration = expiration; }
    public boolean isTitreValide() { return titreValide; }
    public void setTitreValide(boolean titreValide) { this.titreValide = titreValide; }
    public boolean isEnCours() { return enCours; }
    public void setEnCours(boolean enCours) { this.enCours = enCours; }
}
