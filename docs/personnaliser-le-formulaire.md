# Personnaliser le formulaire : onglets, accordéons et disposition

Ce guide explique comment régler l'affichage du formulaire de demande dans e-Services. Tout se règle dans le studio des
procédures, onglet **Formulaire et données** d'une version **brouillon** :

- les onglets, les accordéons et les champs, leur ordre et leurs propriétés, par glisser-déposer dans le
  **Constructeur visuel** (§ 2), ou dans les **Grilles** tabulaires (§ 3) ; les deux vues éditent les mêmes données ;
- leur rendu (onglets, étapes, accordéons, colonnes…), avec le cadre **Gabarit d'IHM** (§ 4).

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

## 2. Le constructeur visuel (glisser-déposer)

Sous le gabarit d'IHM, le sélecteur **Constructeur visuel / Grilles** choisit la vue d'édition (le choix est mémorisé
par le navigateur). Le constructeur, inspiré de form.io, a trois colonnes et un aperçu :

| Zone | Rôle |
|---|---|
| **Composants** (à gauche) | types de champ à glisser dans une section : texte, texte long, nombre entier ou décimal, date, année, liste de codes, adresse électronique, téléphone, oui / non, localisation ; boutons **+ Onglet** et **+ Sous-section** |
| **Canevas** (au centre) | une barre d'onglets (sections de premier niveau), puis, pour l'onglet affiché, ses champs et ses accordéons (sous-sections) avec leurs champs |
| **Propriétés** (à droite) | réglages du champ, de l'onglet ou de l'accordéon sélectionné |
| **Aperçu e-Services** (dessous) | rendu du formulaire du demandeur avec le gabarit d'IHM enregistré |

Le formulaire n'est pas généré sous forme de code : le constructeur produit une description (sections, champs et
réglages d'affichage) que le composant Angular d'e-Services interprète à l'exécution, comme pour les grilles.

### Glisser-déposer

| Je veux… | Geste |
|---|---|
| **Ajouter** un champ | glisser un composant de la palette à l'endroit voulu d'une section, ou **+ Champ** sous la section |
| **Réordonner** les champs | glisser la carte du champ par sa poignée ⋮ |
| **Déplacer** un champ vers un accordéon du même onglet | le glisser dans la liste de l'accordéon |
| **Déplacer** un champ vers un autre onglet | le déposer sur le nom de la section voulue dans la ligne « Déposer un champ dans un autre onglet » (en bas du canevas), ou changer sa *Section* dans les propriétés |
| **Réordonner** les onglets | glisser l'onglet dans la barre d'onglets |
| **Réordonner** les accordéons d'un onglet | glisser l'accordéon par sa poignée |
| **Déplacer** un accordéon vers un autre onglet, ou en faire un onglet | propriété *Section parente* de l'accordéon |
| **Renommer** en place | double-clic sur le libellé (ou crayon), saisir le français et l'anglais, **Entrée** |
| **Dupliquer** ou **supprimer** un champ | boutons de la carte ; la suppression d'une section supprime ses champs (un onglet doit d'abord être vidé de ses accordéons) |

L'ordre est recalculé de 10 en 10 (`sortOrder`) : chaque onglet suivi de ses accordéons, puis les champs section par
section.

### Propriétés d'un champ

| Onglet | Réglages |
|---|---|
| **Affichage** | libellés et aides FR / EN, **texte indicatif** (placeholder) FR / EN, **largeur** dans la grille (Auto, 1 à 3 colonnes, ligne entière), section, code |
| **Données** | type, liste `REF_xxx` ou `@LIEUX_TRAITEMENT`, valeurs locales (`code=libellé FR\|libellé EN`, une par ligne), pré-remplissage depuis le registre des opérateurs, amendable, critère de recherche, **chemin BIE** avec l'assistant SCRDM (bouton **Utiliser**), UNTDED, UID et DEN UN/CCL, modèle OMD |
| **Validation** | obligatoire (oui, non, conditionnel), longueur maximale, format (expression régulière, avec un essai de valeur), **règles DRL** de l'onglet *Règles DRL* qui citent le champ, modèle de règle inter-champs à copier, lien vers l'onglet *Règles DRL* |
| **Logique** | **types de demande** concernés (écrit la condition `typeDemande in (…)`), **affichage conditionnel** « visible si » un autre champ vaut l'une des valeurs choisies (ou est renseigné) |

Les contrôles qui portent sur plusieurs champs (« si A est renseigné, B est obligatoire »…) restent des règles DRL
Drools : le constructeur les liste et propose un modèle, mais on les écrit et on les fait approuver dans l'onglet
*Règles DRL*.

Un champ masqué par l'affichage conditionnel n'est pas contrôlé au dépôt (ni obligation, ni format). Dans un groupe
répétitif, la condition porte d'abord sur le champ de la même ligne.

### Enregistrer

La barre **Enregistrer** (avec un motif facultatif) remplace en une fois les sections, les champs et les réglages
d'affichage. Elle est bloquée si un code est vide ou en double, si un libellé français manque ou si un champ n'a plus de
section. **Annuler les modifications** revient à la version enregistrée. Sur une version qui n'est pas un brouillon, le
constructeur et l'aperçu sont consultables en lecture seule.

La largeur, le texte indicatif et l'affichage conditionnel n'ont pas de colonne dans les tables : ils sont rangés dans le
gabarit d'IHM, sous `ihm.champs` (clé = code du champ), et publiés avec lui :

```json
"ihm": {
  "disposition": "ONGLETS", "sousSections": "ACCORDEONS",
  "champs": {
    "productDescription": { "largeur": 4, "placeholderFr": "Désignation commerciale", "placeholderEn": "Trade name" },
    "licenceNumber": { "visibleSi": { "champ": "hasLicence", "valeurs": ["true"] } }
  }
}
```

`largeur` vaut 1 à 4 colonnes ; une largeur égale ou supérieure au nombre de colonnes du gabarit occupe toute la ligne,
et tout champ revient à une colonne sur mobile. Sans largeur, un texte long ou une localisation occupe toute la ligne.

### Aperçu

L'aperçu reproduit le rendu d'e-Services : disposition, onglets ou étapes, accordéons, colonnes, densité, libellés,
largeurs, textes indicatifs. On peut y choisir le **type de demande** et la **nature** simulés, saisir des valeurs
d'essai pour vérifier l'affichage conditionnel (elles ne sont pas conservées) et cliquer sur un champ pour le
sélectionner. Les listes du Référentiel Commun et la carte de localisation y sont figurées sans être chargées. Le
constructeur affiche toujours onglets et accordéons ; si le gabarit d'IHM enregistré est différent, un bandeau invite à
choisir le modèle « Onglets et accordéons ».

## 3. Nommer, ordonner, ajouter ou retirer des onglets (grilles)

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

## 4. Choisir le rendu : le gabarit d'IHM

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

## 5. Réutiliser un gabarit : import et export JSON

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
sous-sections en blocs). Un ancien gabarit sans `sousSections` garde donc son rendu. L'export et l'import ne portent que
sur la disposition : les réglages par champ du constructeur (`ihm.champs`) restent ceux de la version, et
l'enregistrement du gabarit les conserve.

## 6. Vérifier

1. Enregistrez le constructeur (ou la grille des sections) et le gabarit, puis regardez l'aperçu e-Services du
   constructeur ou celui du gabarit d'IHM.
2. Lancez la revue (onglet *Agents IA*) : le Compliance Agent contrôle les sous-sections (un seul niveau, parent connu).
3. Après publication, ouvrez une nouvelle demande dans e-Services : les onglets et accordéons suivent l'ordre et les
   libellés de la grille.
