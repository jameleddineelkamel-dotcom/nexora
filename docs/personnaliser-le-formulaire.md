# Personnaliser le formulaire : onglets, accordéons et disposition

Ce guide explique comment régler l'affichage du formulaire de demande dans e-Services. Tout se règle dans le studio des
procédures, onglet **Formulaire et données** d'une version **brouillon** :

- le nom, l'ordre et le nombre des onglets et des accordéons, avec la grille **Sections et sous-sections** ;
- leur rendu (onglets, étapes, accordéons, colonnes…), avec le cadre **Gabarit d'IHM**.

Une version publiée est en lecture seule : on peut y essayer un gabarit dans l'aperçu, mais pas l'enregistrer. Pour
modifier une procédure publiée, cliquez sur **Nouvelle version** depuis la page de la procédure, faites les changements
dans ce brouillon, puis faites-le approuver et publier. Le Runtime applique la nouvelle disposition dès la publication.

## 1. Le principe : sections → onglets, sous-sections → accordéons

| Élément du studio | Affichage avec le modèle « Onglets et accordéons » |
|---|---|
| Section de **premier niveau** (colonne *Section parente* vide) | un **onglet** |
| **Sous-section** (colonne *Section parente* renseignée) | un **accordéon** dans l'onglet de sa section parente |
| Champs rattachés directement à une section de premier niveau | affichés en haut de l'onglet, au-dessus des accordéons |
| Champs rattachés à une sous-section | affichés à l'ouverture de l'accordéon |

Exemple, sur le modèle d'une déclaration EUCDM :

```
Onglet « Niveau général »          ← section NIVEAU_GENERAL (premier niveau)
  ▸ Groupe 11 Informations sur les messages        ← sous-section G11, parent NIVEAU_GENERAL
  ▸ Groupe 12 Références de messages, documents…    ← sous-section G12, parent NIVEAU_GENERAL
  ▸ Groupe 13 Parties                               ← sous-section G13, parent NIVEAU_GENERAL
Onglet « Niveau articles »         ← section NIVEAU_ARTICLES (premier niveau)
  ▸ Groupe 18 Identification des marchandises       ← sous-section G18, parent NIVEAU_ARTICLES
```

Il n'y a qu'**un niveau** de sous-sections : une sous-section ne peut pas avoir elle-même de sous-sections. Le Compliance
Agent signale une sous-section rattachée à une autre sous-section ou à une section inconnue.

## 2. Nommer, ordonner, ajouter ou retirer des onglets

Tout se fait dans la grille **Sections et sous-sections** de l'onglet *Formulaire et données*. Enregistrez ensuite la
grille.

| Je veux… | Action dans la grille |
|---|---|
| **Renommer** un onglet ou un accordéon | modifier *Libellé (FR)* et *Libellé (EN)* ; e-Services affiche le libellé de la langue choisie par l'utilisateur, ou le français à défaut |
| **Changer l'ordre** des onglets | boutons **Monter / Descendre** de la ligne ; l'ordre est enregistré de 10 en 10 (`sortOrder`) |
| **Changer l'ordre** des accordéons d'un onglet | même chose : les sous-sections s'affichent dans l'ordre de la grille |
| **Ajouter** un onglet | **Ajouter**, saisir un *Code* (majuscules, ex. `NIVEAU_ARTICLES`) et les libellés, laisser *Section parente* vide |
| **Ajouter** un accordéon | **Ajouter**, puis choisir la *Section parente* (l'onglet qui le contient) |
| **Déplacer** un accordéon vers un autre onglet | changer sa *Section parente* |
| **Transformer** un accordéon en onglet | vider sa *Section parente* |
| **Retirer** un onglet ou un accordéon | rattacher d'abord ses champs à une autre section (colonne *Section* de la grille des champs), puis **Supprimer** la ligne |
| Un **tableau de lignes** (ex. articles, produits) | cocher *Répétitif* et, si besoin, *Lignes min.* ; le tableau s'affiche dans l'onglet ou dans l'accordéon |

Le **nombre d'onglets** est le nombre de sections de premier niveau qui ont quelque chose à afficher. e-Services masque
automatiquement :

- une section sans champ applicable à la demande, sauf si elle contient des sous-sections visibles ;
- la section `DEMANDE` d'une demande initiale (elle ne porte que les champs de renouvellement et d'amendement) ;
- la section `INSTRUCTION` pour le demandeur (elle est saisie par l'organisme instructeur) ;
- les champs conditionnés `typeDemande in (…)` qui ne concernent pas le type de demande choisi.

Une sous-section dont la section parente n'existe plus remonte au premier niveau : elle devient un onglet.

## 3. Choisir le rendu : le gabarit d'IHM

Le cadre **Gabarit d'IHM** propose des modèles prêts à l'emploi ; un clic sur un modèle remplit les réglages, que l'on
peut ensuite ajuster. L'aperçu sous les réglages est interactif : on peut cliquer sur les onglets, avancer dans les
étapes et ouvrir les accordéons.

| Modèle | Disposition | Sous-sections |
|---|---|---|
| Formulaire GUCE compact | sections à la suite, pastilles de navigation, suivi à droite | blocs |
| Assistant pas à pas | une section par étape, Précédent / Suivant | blocs |
| Onglets | une section de premier niveau par onglet | blocs |
| **Onglets et accordéons** | une section de premier niveau par onglet | **accordéons**, tous fermés |
| Saisie dense (agents) | sections à la suite, 4 colonnes, libellés à gauche, sections repliables | blocs |

| Réglage | Valeurs | Effet |
|---|---|---|
| Disposition | Continue, Onglets, Assistant par étapes | rendu des sections de premier niveau |
| Colonnes | 1 à 4 | nombre de champs par ligne (1 colonne sur mobile) |
| Densité | Compacte, Normale, Aérée | hauteur des champs et espacements |
| Libellés | Au-dessus, À gauche du champ | position des libellés |
| **Sous-sections** | Blocs dans la section, **Accordéons** | rendu des sous-sections |
| **Accordéons ouverts** | Aucun (tous fermés), Le premier de chaque section, Tous | état initial des accordéons ; un accordéon qui contient une erreur s'ouvre de lui-même tant que l'utilisateur ne l'a pas refermé, et son en-tête affiche le nombre d'erreurs |
| Pastilles de navigation | oui / non | liens vers les sections (disposition continue) |
| Suivi à droite | oui / non | position du panneau de suivi du dossier |
| Sections repliables | oui / non | titres de sections cliquables (disposition continue) |

Cliquez sur **Enregistrer** pour mémoriser le gabarit dans le réglage `ihm` de la version.

## 4. Réutiliser un gabarit : import et export JSON

Le bouton **JSON** télécharge le gabarit ; le bouton **Importer** (brouillon seulement) le recharge dans une autre
procédure. Format :

```json
{
  "format": "nexora.ihm/1",
  "ihm": {
    "disposition": "ONGLETS",
    "colonnes": 3,
    "densite": "COMPACTE",
    "libelles": "DESSUS",
    "navigation": false,
    "resume": false,
    "repliables": false,
    "sousSections": "ACCORDEONS",
    "accordeons": "AUCUN"
  }
}
```

Une valeur absente ou inconnue est remplacée par celle du gabarit par défaut (formulaire continu, 3 colonnes, compact,
sous-sections en blocs). Un ancien gabarit sans `sousSections` garde donc son rendu.

## 5. Vérifier

1. Enregistrez la grille des sections et le gabarit, puis regardez l'aperçu du gabarit d'IHM.
2. Lancez la revue (onglet *Agents IA*) : le Compliance Agent contrôle les sous-sections (un seul niveau, parent connu).
3. Après publication, ouvrez une nouvelle demande dans e-Services : les onglets et accordéons suivent l'ordre et les
   libellés de la grille.
