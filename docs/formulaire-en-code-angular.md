# Formulaire en code Angular généré

Par défaut, e-Services construit le formulaire de demande **à l'exécution** à partir du paquet publié par le studio
(rendu **dynamique**) : aucune recompilation, et chaque version s'affiche telle qu'elle a été publiée. Pour une procédure
donnée, un administrateur de la plateforme peut choisir à la place le rendu **code généré** : le studio écrit alors un
vrai composant Angular pour chaque version publiée, qui est compilé dans e-Services.

| | Dynamique (recommandé) | Code Angular généré |
|---|---|---|
| Mise en service d'une version | publier, déployer | publier, déployer, **préparer le code, reconstruire et redéployer e-Services** |
| Code source du formulaire | aucun (interprétation du paquet) | un composant par version, lisible et versionnable (`src/app/formulaires-generes`) |
| Chargement | composant commun | bundle propre à la version, chargé à la demande |
| Si le composant manque | — | repli automatique sur le rendu dynamique |

Les deux rendus reçoivent les mêmes données et produisent les mêmes saisies ; la validation au dépôt (obligation,
format, listes, affichage conditionnel, règles DRL) est faite par le serveur dans les deux cas.

## 1. Choisir le rendu (studio, administrateur)

Onglet **Formulaire et données** d'une version **brouillon**, cadre **Rendu dans e-Services** : *Dynamique* ou
*Code Angular généré*, puis **Enregistrer**. Le choix est rangé dans le réglage `rendu` de la version
(`DYNAMIQUE` ou `GENERE`), recopié dans les versions suivantes et publié avec le paquet. Seul un utilisateur ayant le
droit d'administration de la plateforme (`auth.administration`) peut le modifier ; le serveur refuse le changement à
tout autre utilisateur.

Le bouton **Voir le code Angular généré** (tout modélisateur, toute version) affiche et télécharge les fichiers produits
pour la version : un composant `.ts` et son gabarit `.html`.

## 2. Ce que contient le code généré

Pour la procédure `FIMEX` en version 1, le studio produit `fimex-v1.ts` (classe `FormulaireFimexV1`, sélecteur
`nx-fg-fimex-v1`) et `fimex-v1.html` :

- la **structure** selon le gabarit d'IHM de la version : onglets, étapes ou sections à la suite ; sous-sections en
  blocs ou en accordéons ; nombre de colonnes, densité, position des libellés ;
- un **contrôle écrit pour chaque champ** selon son type (texte, date, nombre, liste locale, liste `REF_xxx` du
  Référentiel Commun, lieux de traitement, oui / non, localisation, texte long), avec sa largeur, son texte indicatif,
  son obligation, son verrouillage en amendement, son aide et ses messages d'erreur ;
- les **groupes répétitifs** en tableaux de lignes ;
- les **conditions en code** : types de demande concernés (`typeDemande in (…)`) et affichage conditionnel « visible si » ;
- les **libellés** (français et anglais), aides, textes indicatifs et valeurs de listes en constantes TypeScript : les
  textes saisis dans le studio ne sont jamais écrits dans le gabarit HTML.

Le composant étend `FormulaireGenereBase` (`eservices/frontend/src/app/formulaires-generes/base.ts`), qui porte la
mécanique commune : saisie, lignes des groupes, erreurs, amendement, navigation, accordéons et onglets « Pièces jointes »
et « Livrables » ajoutés par la page du dossier. Les styles communs sont dans `formulaire-genere.css`.

La génération est refusée si un code de champ ou de section n'est pas un identifiant (lettres, chiffres, `_`), ou si une
liste n'est ni `REF_xxx` ni `@LIEUX_TRAITEMENT`. Le fichier généré porte en tête la mention « Ne pas modifier à la
main » : toute évolution se fait dans le studio, puis on régénère.

## 3. Intégrer le code dans e-Services

1. Publier la version dans le studio, puis la déployer dans e-Services (*Administration › Procédures*).
2. Dans *Administration › Formulaires générés*, cliquer sur **Préparer le code généré**. e-Services demande au studio,
   avec le jeton de l'administrateur, le code de chaque version déployée en rendu `GENERE`, l'écrit dans
   `eservices/frontend/src/app/formulaires-generes` (réglage `nexora.formulaires-generes.dossier`, variable
   `FORMULAIRES_GENERES_DOSSIER`) et réécrit le registre `index.ts`.
3. Reconstruire et redéployer e-Services (`construire.cmd` puis `lancer.cmd`). La colonne « Compilé dans cette version
   d'e-Services » passe à *Oui*.

Cette action se lance là où se trouvent les sources d'e-Services (poste de développement ou serveur de construction) ;
sur un serveur sans sources, le dossier est signalé « introuvable » et le bouton reste inactif. Les fichiers générés
peuvent être versionnés avec le reste du code. Une version déjà compilée reste servie par son composant même après la
publication d'une version suivante : chaque dossier s'affiche avec le formulaire de sa version.

## 4. Points d'attention

- Revenir au rendu dynamique ne demande qu'une nouvelle version avec le rendu *Dynamique* : aucune reconstruction.
- Le format du code est versionné (`format` 1, en tête de fichier) : une évolution de `FormulaireGenereBase` qui casse
  ce contrat impose de régénérer les composants existants.
- Le réglage « sections repliables » du gabarit d'IHM n'est pas repris dans le code généré.
