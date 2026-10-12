# Cas d'application : FIMEX (Fichier des importateurs et des exportateurs)

Sources : *SFD FIMEX e-GUCE 3G v1.4* (28/09/2026) et *DM_FIMEX_eGUCE3G v1.1*. FIMEX est livré comme procédure de
référence : il est amorcé dans le studio en **version 1 brouillon** (`procedures/backend/src/main/resources/seed/fimex/`)
et suit ensuite le circuit normal (agents, revue, soumission, approbation, publication, déploiement).

## De la SFD au modèle de procédure

| SFD | Modèle NEXORA |
|---|---|
| Circuit Préalables | circuit `PREALABLES` (phase *Préalables*, processus *Formalités transversales*, BSP : Buy) |
| Déclinaisons importateurs / exportateurs / autorisation spéciale | types de demande `IMP` (CUR `IFI`), `EXP` (`IFE`), `AS` (`ASC`) — RG6 : `CM2026IFI000001` |
| UC-01 inscription, UC-02 renouvellement, UC-03 amendement | natures `INITIAL`, `RENEWAL` (IMP, EXP), `AMENDMENT` |
| Acteurs (opérateur / CDA, DCE-SOC, CNCC, GUCE, DGI, banques) | 9 acteurs, dont agent de traitement, signataire, registre des opérateurs |
| Composition du dossier P1…P8 | 8 pièces ; P7 conditionnelle (`foreignerCitizen == 'E'`), P8 « le cas échéant » |
| Délais et coûts | `slaHours = 48` ; frais MINCOMMERCE 27 000, GUCE 3 000 (10 % de 30 000 déduits), CNCC 15 000 (IMP, AS), timbre DGI 1 500 → **46 500 / 31 500 / 46 500 FCFA** ; amendement non payant |
| Formulaire (onglets informations générales, produits, pièces) + « lieu de traitement » (v1.3) + localisation intelligente (v1.3) | 6 sections, 40 champs du DM FIMEX (UNTDED, OMD, DEN UN/CCL), groupe répétitif `PRODUITS` (SH ≥ 6 chiffres, RM1), `processingLocation` (liste dynamique `@LIEUX_TRAITEMENT`), `location` (GPS ou saisie avec autocomplétion) |
| RG / RM des cas d'utilisation | 16 règles de gestion en clair, reliées aux règles DRL (`fimex.drl`) |
| BPMN eFORCE / eGOV | workflow généré : frais → avis de paiement → paiement ou annulation au délai → cotation → traitement (complément / validation / rejet) → signature (validation / retour) → délivrance |
| UC-04.1 cotation aléatoire et équitable par unité | délégué `${cotation}` ; 10 régions + administration centrale, chacune rattachée à une unité |
| UC-05 RG1 attestation (QR, signataire, cachet, dates) ; RG2 mise à jour du référentiel | livrable `ATTESTATION` (code `ATT`, validité année budgétaire) ; message `FIMEX_PARTY_UPDATE` + complétion du registre |

## Règles exécutables (Drools)

| Règle DRL | SFD |
|---|---|
| RG1 — Pas de renouvellement des autorisations spéciales | UC-01 / UC-02 RG1 |
| RG4 — Un opérateur ne dépose pas pour un tiers (sauf CDA) | UC-01 RG4, RG5 |
| RG05 — Année de renouvellement n+1 | UC-02 RG05 |
| RG07 — Un seul renouvellement en cours ou validé par année | UC-02 RG07 |
| UC-01 — Pas de nouvelle inscription si une attestation est valide ou en cours | UC-01 |
| UC-02 / UC-03 — Renouvellement et amendement exigent une inscription validée / une attestation valide | UC-02, UC-03 |
| Pièce P7 conditionnelle | composition du dossier |
| RM1 — au moins un produit ; pays d'origine (import) / de destination (export) | UC-01 RM1 |
| Frais MINCOMMERCE, GUCE, CNCC, TIMBRE (natures INITIAL, RENEWAL) | délais et coûts ; UC-03 RG1 |

Le test unitaire `FimexModeleTest` vérifie ces règles, le BPMN généré et l'extraction de la SFD par le Document Agent
(8 pièces, 6 acteurs, règles RG / RM, frais exacts).

## Messages et formats

| Message | Format | Flux | Émetteur → destinataire |
|---|---|---|---|
| `FIMEX_REQUEST` (FimexRequest) | JSON | Dépôt | demandeur → MINCOMMERCE (après paiement, avec les pièces) |
| `FIMEX_PAYMENT_NOTICE` | XML | Paiement | GUCE → demandeur |
| `PAYMENT_CONFIRMATION` | XML | Paiement | banque → GUCE (message entrant, idempotent) |
| `FIMEX_COMPLEMENT` | TXT | Instruction | agent de traitement → demandeur |
| `FIMEX_DECISION` | JSON | Instruction | agent de traitement → demandeur (rejet) |
| `FIMEX_CERTIFICATE` (FimexCertificate) | JSON | Délivrance | signataire → demandeur |
| `FIMEX_PARTY_UPDATE` | JSON | Délivrance | MINCOMMERCE → registre des opérateurs |
| `FIMEX_ENCAISSEMENTS` | CSV | Reversement | GUCE → bénéficiaires (relevé) |

## Modèle de données DM FIMEX → vues métier

Au déploiement, le Runtime crée le schéma `proc_fimex` :

| Entité du DM FIMEX | Vue | Source générique |
|---|---|---|
| FIMEX_REQUEST | `proc_fimex.fimex_request` (un attribut du DM = une colonne `snake_case`) | `wfe.dossier.form_data` |
| FIMEX_COMMODITY | `proc_fimex.fimex_commodity` | groupe répétitif `PRODUITS` |
| FIMEX_PAYMENT_NOTICE | `proc_fimex.fimex_payment_notice` | `wfe.payment_notice` |
| FIMEX_CERTIFICATE | `proc_fimex.fimex_certificate` | `wfe.issued_document` |

## Scénario de démonstration

Comptes : `database\creer-comptes-test.cmd` (mot de passe `NEXORA_TEST_PASSWORD` de `auth\.env.local`).

1. **Studio** (`test.concepteur`) : version 1 de FIMEX › Agents IA › joindre la SFD › exécuter l'orchestrateur ›
   accepter les rapprochements SCRDM et les listes du Référentiel Commun › Contrôles et publication › Soumettre.
2. **Studio** (`test.approbateur`) : Approuver puis Publier.
3. **e-Services** (`test.adminesv`) : Administration › Procédures › Déployer FIMEX.
4. **eFORCE** (`test.operateur`) : Nouvelle demande › FIMEX › Inscription au fichier des importateurs › formulaire
   pré-rempli (SCIE SA) › produits, lieu de traitement › Vérifier et déposer (46 500 FCFA) › Payer en ligne.
5. **eGOV** (`test.agent`) : Mes tâches › Traiter la demande › Aperçu du livrable (brouillon) › Valider.
6. **eGOV** (`test.signataire`) : Valider et signer → attestation `CM2026IFI000001ATT` (PDF, code QR), statut Validé,
   registre complété (boîte postale, chiffre d'affaires…).
7. **Vérification publique** : http://localhost:8085/verifier/CM2026IFI000001ATT.
8. **Supervision** (`test.superviseur`) : encaissements par bénéficiaire, relevé CSV, journal des messages.
