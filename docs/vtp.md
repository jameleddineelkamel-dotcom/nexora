# Cas d'application : VTP (Visa Technique Provisoire MINSANTE)

Sources : *SFD IMPORT-TRANSACTION VTP e-GUCE 3G v1.2* (20/08/2026), *SFD IMPORT-TRANSACTION e-GUCE 3G v1.3* (circuit
Transaction), modèles de données *DM_TRANSACTION eForce v1.0*, *DM_TRANSACTION eGov v1.0*, *DM_MS_DOSSIER eForce v1.0*,
maquette *VTP_Wireframes_Navigation* (écrans EF01 à EF08) et diagramme *visa-minsante-provisoire-process.bpmn*.

La procédure a été créée dans le studio (code `VTP`, version 1 **brouillon**) à partir du gabarit standard, puis
modélisée par les agents IA avec revue humaine de chaque proposition, et complétée à la main pour ce que les agents ne
pouvaient pas déduire. Les agents ont fonctionné en mode heuristique (aucun modèle de langage configuré).

## De la SFD au modèle de procédure

| SFD et pièces | Modèle NEXORA |
|---|---|
| IMP-TRA-004, MINSANTE / DPML, émis en amont dans le circuit Transaction | procédure `VTP`, circuit `TRANSACTION` (phase pré-dédouanement à l'import), autorité MINSANTE — DPML |
| Natures NEW, AMENDEMENT / MODIFICATION (UC-01, UC-06) | type de demande `VTP` (CUR `VTP`), natures `INITIAL` et `AMENDMENT` |
| Acteurs : importateur / CDA, agent de recevabilité, agent de traitement DPML, signataire, GUCE, e-GOV, Douane, SGS | `DEMANDEUR`, `AGENT_RECEVABILITE`, `AGENT_TRAITEMENT`, `SIGNATAIRE` (e-GOV), `GUCE`, `ADMINISTRATION` (MINSANTE — DPML), `DOUANE`, `SGS`, `BANQUE`, `REGISTRE` |
| Composition du dossier P1 à P4 | P1 fiche technique, P2 facture proforma, P3 autorisation spéciale d'importation (obligatoires) ; P4 dérogation du MINSANTE, exigée si `derogationIndicator == 'true'` |
| Délai 48 h ; livrable valable pour le seul dossier d'importation | `slaHours = 48` ; livrable `VTP` (code document `VTP`, validité `AUCUNE`, mention « valable uniquement pour le dossier d'importation indiqué ») |
| Maquette EF02 « Initier une demande » et modèles de données | 14 sections en 7 onglets (Importateur, Fournisseur, Transport, Transaction financière, Produits pharmaceutiques, Visa technique provisoire, Traitement) avec sous-sections en accordéons ; 60 champs |
| `TRANSACTION_COMMODITY` (marchandises) | groupe répétitif `PRODUITS` (au moins 1 ligne) : `hsTariffCode` (SH n10), `detailedDescription`, `refAMM` (liste `REF_AMM`), `quantity`, `quantityUnit` (`REF_UNIT`) ; additifs (numéros INS / E) |
| `PROCEDURE_VTP` | section « Visa technique provisoire » : `transactionNumber`, `derogationIndicator`, `aoiNumber`, `approvalNumber`, `approvalIssueDate`, `pharmacistName` ; modification : `previousRequestNumber`, `amendmentReason` |
| EF04 signature : conditions du visa définitif | champ d'instruction `conditionsVisaDefinitif` (section `INSTRUCTION`, saisie par l'organisme), imprimé sur le VTP |
| Frais | aucun montant dans la SFD : ligne GUCE à 0, pas d'avis de paiement |
| BPMN fourni (couloirs importateur / GUCE / MINSANTE) | workflow `vtp` importé par le Process Agent puis adapté au moteur (voir ci-dessous) ; statut ajouté `EN_ATTENTE_RECEVABILITE` |
| RG002, UC-07 R5 : Douane et SGS notifiées | messages `VTP_AVIS_DOUANE`, `VTP_AVIS_SGS` (JSON) dans le flux `DELIVRANCE`, émis à la délivrance avec `VTP_DOCUMENT` |

## Workflow

Le Process Agent a lu le BPMN fourni (Camunda Modeler, plate-forme Camunda 8). Tel quel, il n'était pas exécutable par
le moteur Operaton du Runtime (tâches *external* à topics, tâches humaines sans formulaire ni décisions, groupes
inconnus du SSO). La proposition a été **corrigée** en conservant les couloirs, les étapes et leur documentation :

| BPMN fourni | Workflow NEXORA `vtp` |
|---|---|
| Renseigner, joindre les pièces, soumettre ; vérifier éligibilité et complétude ; notifier un dossier incomplet | formulaire e-Services et contrôles au dépôt (champs, pièces, règles DRL VTP-01 à VTP-04) : le processus démarre sur une demande complète |
| Cotation automatique (*external*) | `${cotation}` (agent MINSANTE / DPML et signataire), statut `A_COTER` |
| — | passerelle *Nature* : une **modification** va directement au signataire (UC-06, UC-07) |
| Recevabilité documentaire | tâche `nexora:traitement` de l'agent coté, décisions complément / validation, statut `EN_ATTENTE_RECEVABILITE` |
| Évaluer conformité sanitaire | étude approfondie (`nexora:traitement`), décisions complément / validation pour signature, saisie des conditions du visa définitif, statut `EN_ATTENTE_TRAITEMENT` |
| Demander / répondre au complément (un seul retour) | un complément par étape : notification `VTP_COMPLEMENT`, tâche de l'opérateur, retour **à l'étape qui l'a demandé** (UC-08) |
| Décision finale, délivrer le visa | signature (`nexora:signature`) : validation, retour pour réexamen à l'agent de traitement (RG001) ou rejet (`VTP_DECISION`, statut `REJETE`) ; délivrance `${delivrance}` du livrable `VTP` à code QR, statut `VALIDE` |

Limites du moteur actuel : recevabilité et étude approfondie sont confiées au même agent coté (une seule cotation par
dossier) ; l'observation est exigée par le Runtime pour un complément, un retour ou un rejet, pas pour une validation
(la SFD l'exige aussi pour la validation).

## Règles exécutables (Drools)

| Règle DRL | SFD |
|---|---|
| Pièce P4 conditionnelle (générée par le Rules Agent) | UC-01 : dérogation → pièce « dérogation du MINSANTE » |
| VTP-01 — Code AMM obligatoire pour chaque produit sauf dérogation | UC-01 (AMM actives, dérogation), ERR-VTP-002 |
| VTP-02 — Modification : VTP d'origine obligatoire | UC-06 R1 |
| VTP-03 — Modification : observation obligatoire | UC-06 R2 |
| VTP-04 — FIMEX import en cours de validité | UC-01 (pré-condition), ERR-VTP-001 |

Vérifiées au banc d'essai : AMM manquante sans dérogation (erreur), dérogation avec P4 (accepté), dérogation sans P4
(erreur P4), modification incomplète (2 erreurs), FIMEX expiré (erreur), dossier conforme (accepté).

Règles de gestion sans règle DRL : UC-04 RG001 à RG005 (couvertes par le workflow, la notification à la délivrance et le
livrable à code QR) ; UC-01 « désignation conforme à l'AMM » (contrôle de l'agent de traitement) ; « une seule demande
de VTP active par dossier de transaction » (le fait Drools ne connaît pas les autres dossiers de la transaction).

## Revue des propositions des agents

- **Document Agent** (SFD, maquette, modèles de données) : 99 propositions. Sections et champs de la maquette acceptés,
  bloc produits corrigé sur les attributs du modèle de données ; pièces P1 à P3 corrigées selon la SFD VTP (l'agent
  avait repris celles du circuit Transaction), P5 « bulletin orange » rejetée ; acteurs renommés ; règles du circuit
  (DI SGS, taxe d'inspection, Incoterms, liste des dossiers) rejetées.
- **Data Agent** : 24 rapprochements SCRDM D23B ; 12 acceptés, 1 corrigé (pays de destination → pays d'importation),
  13 rejetés (correspondances fausses) ; 29 champs sans équivalent SCRDM.
- **Rules Agent** : DRL régénéré et accepté.
- **Process Agent** : BPMN fourni importé, proposition corrigée pour le moteur (voir « Workflow »).
- **Compliance Agent** : version conforme, 3 alertes, aucune erreur bloquante.

## Points ouverts

1. Listes `REF_AMM`, `REF_UNIT`, `REF_BANK`, `REF_BANK_AGENCY` à vérifier dans le Référentiel Commun.
2. Référence UNTDED `7082` (description) reprise du modèle de données : inconnue de l'UNTDED 2005, à confirmer.
3. Approbation des règles DRL puis de la version par un utilisateur autre que l'auteur, avant publication.
4. Une seule demande de VTP active par dossier de transaction : à contrôler côté circuit Transaction (hors règles DRL de la procédure).
