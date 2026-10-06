/** Dictionnaire anglais de l'interface (clé = texte source français). */
export const EN: Record<string, string> = {
  // Structure générale
  'Référentiel Commun': 'Common Reference Data', 'Microservice refdata': 'refdata microservice', 'Plateforme NEXORA': 'NEXORA platform',
  'Procédures': 'Procedures', 'Services DATA': 'DATA services', 'Tableau de bord': 'Dashboard', 'Catalogue': 'Catalogue',
  'Buy · Ship · Pay': 'Buy · Ship · Pay', 'Historique': 'History', 'Chargements': 'Data loads', 'Nouvelle table': 'New table',
  'Menu': 'Menu', 'Langue': 'Language', 'Mode sombre': 'Dark mode', 'Mode clair': 'Light mode',
  'Rechercher une table, un code, un libellé…': 'Search a table, a code, a label…', 'Votre nom (historique)': 'Your name (history)',
  'Votre nom': 'Your name', "Auteur enregistré dans l'historique": 'Author recorded in the history', 'Recherche globale': 'Global search',
  'Pays, devise, port, Incoterm, code UN/LOCODE…': 'Country, currency, port, Incoterm, UN/LOCODE code…', 'Échap': 'Esc',
  'Recherche dans toutes les tables et tous les codes du référentiel, sans tenir compte des accents.': 'Searches every table and code of the reference data, ignoring accents.',
  'Exemples': 'Examples', 'Aucun résultat pour « {0} ».': 'No result for “{0}”.',

  // Tableau de bord
  'NEXORA · Référentiel Commun du commerce extérieur': 'NEXORA · Common Reference Data for foreign trade',
  'Des codes communs pour toute la chaîne logistique': 'Shared codes for the whole supply chain',
  "Listes de codes internationales (ISO, UN/CEFACT, OMD), régionales (CEMAC) et nationales, décrites selon l'ISO 19115, historisées et exposées aux autres services de la plateforme.":
    'International (ISO, UN/CEFACT, WCO), regional (CEMAC) and national code lists, described according to ISO 19115, versioned and exposed to the other platform services.',
  'Explorer le catalogue': 'Explore the catalogue', 'Créer une table': 'Create a table', '{0} tables': '{0} tables',
  'tables de référence': 'reference tables', '{0} alimentées': '{0} populated', 'codes': 'codes', '{0} invalidés': '{0} invalidated',
  'modifications': 'changes', 'sur 7 jours': 'over 7 days', 'complétude ISO 19115': 'ISO 19115 completeness', 'moyenne des fiches': 'average of the records',
  'Catégories': 'Categories', 'Tout voir': 'See all', '{0} tables · {1} codes': '{0} tables · {1} codes', 'Activité': 'Activity',
  '30 derniers jours (hors chargement initial)': 'Last 30 days (excluding initial load)', 'Aucune modification récente.': 'No recent change.',
  'Historique complet': 'Full history', 'Tables les plus volumineuses': 'Largest tables', 'Fiches de métadonnées à compléter': 'Metadata records to complete',
  'Indicateur de qualité : part des 26 rubriques ISO 19115 renseignées.': 'Quality indicator: share of the 26 ISO 19115 fields filled in.',

  // Catalogue
  'Catalogue des tables de référence': 'Reference table catalogue',
  '{0} table(s) · rapport « Référentiel Commun » (ISO 19115), recommandations UN/CEFACT, normes CEMAC et nationales.':
    '{0} table(s) · “Common Reference Data” report (ISO 19115), UN/CEFACT recommendations, CEMAC and national standards.',
  'Toutes les catégories': 'All categories', 'Filtrer par nom, code, standard (ex. Rec. 20, ISO 4217, CEMAC)…': 'Filter by name, code, standard (e.g. Rec. 20, ISO 4217, CEMAC)…',
  'Filtrer': 'Filter', 'Source': 'Source', 'Toutes sources': 'All sources', 'Contenu': 'Content', 'Tout contenu': 'All content',
  'Alimentées': 'Populated', 'À alimenter': 'To populate', 'Simples': 'Simple', 'Complexes': 'Complex', 'Cartes': 'Cards', 'Liste': 'List',
  '{0} codes': '{0} codes', 'Complexe': 'Complex', 'Simple': 'Simple', 'Aucune table ne correspond à ces filtres.': 'No table matches these filters.',
  'Code': 'Code', 'Nom': 'Name', 'Standards': 'Standards', 'Codes': 'Codes', 'Internationale': 'International', 'Régionale': 'Regional', 'Nationale': 'National',

  // Page d'une table
  'Retour au catalogue': 'Back to the catalogue', 'Fiche {0}': 'Record {0}', 'Contenu complexe': 'Complex content', 'Contenu simple': 'Simple content',
  'Archivée': 'Archived', 'Brouillon': 'Draft', 'actifs': 'active', 'colonnes': 'columns', 'Données': 'Data', 'Structure': 'Structure',
  'Métadonnées ISO 19115': 'ISO 19115 metadata', 'Import / export': 'Import / export', 'API & SQL': 'API & SQL',
  'Créée le {0} · modifiée le {1}': 'Created on {0} · modified on {1}',

  // Données
  'Rechercher un code ou un libellé (sans accents)…': 'Search a code or a label (accents ignored)…', 'Rechercher': 'Search', 'Statut': 'Status',
  'Tous': 'All', 'Actifs': 'Active', 'Invalidés': 'Invalidated', 'Afficher les codes valides à cette date (voyage dans le temps)': 'Show the codes valid on this date (time travel)',
  'Valide au': 'Valid on', 'Nouveau code': 'New code', 'Racine': 'Root', 'Vue à plat': 'Flat view', 'Parent': 'Parent', 'Validité': 'Validity',
  'Voir les sous-codes': 'Show the sub-codes', 'Actif': 'Active', 'Invalidé': 'Invalidated', 'Chargement…': 'Loading…',
  "Cette table n'est pas encore alimentée. Ajoutez des codes un par un ou chargez un fichier depuis l'onglet « Import / export ».":
    'This table is not populated yet. Add codes one by one or load a file from the “Import / export” tab.',
  'Aucun code ne correspond à ces critères.': 'No code matches these criteria.', '{0} code(s) · page {1} / {2}': '{0} code(s) · page {1} / {2}',
  'Précédent': 'Previous', 'Suivant': 'Next', '{0} enregistré': '{0} saved', 'Oui': 'Yes', 'Non': 'No', 'Permanente': 'Permanent',
  'du {0}': 'from {0}', 'au {0}': 'to {0}',

  // Fiche d'un code
  'Fermer': 'Close', 'Détail': 'Details', 'Historique du code': 'Code history', 'Balise': 'Tag', 'Code parent ({0})': 'Parent code ({0})',
  'Code parent': 'Parent code', 'Attributs': 'Attributes', 'Début de validité': 'Valid from', 'Fin de validité': 'Valid to',
  'Motif de la modification (historique)': 'Reason for the change (history)', 'Ex. mise à jour ISO 3166 du 2026-06-01': 'E.g. ISO 3166 update of 2026-06-01',
  'Version {0} · créé le {1} · modifié le {2}': 'Version {0} · created on {1} · modified on {2}', "Date d'invalidation": 'Invalidation date',
  "Confirmer l'invalidation": 'Confirm the invalidation', 'Invalider': 'Invalidate', 'Réactiver': 'Reactivate', 'Annuler': 'Cancel', 'Enregistrer': 'Save',

  // Structure
  'Structure de la table': 'Table structure',
  'Le code est la clé ; les libellés FR/EN servent aux listes ; les attributs font de la liste une liste « complexe ». Chaque modification est historisée et la table physique {0} est reconstruite.':
    'The code is the key; FR/EN labels feed the lists; attributes make the list a “complex” one. Every change is recorded and the physical table {0} is rebuilt.',
  'Libellés': 'Labels', 'Motif de la modification': 'Reason for the change', 'Ex. ajout du code IATA (Rec. 16 Rév. 4)': 'E.g. IATA code added (Rec. 16 Rev. 4)',
  'Confirmer le retrait et effacer ces valeurs': 'Confirm the removal and erase these values', 'Structure enregistrée, table physique reconstruite.': 'Structure saved, physical table rebuilt.',
  'Enregistrer la structure': 'Save the structure', 'Annuler les changements': 'Discard the changes',
  'Monter': 'Move up', 'Descendre': 'Move down', 'Libellé de la colonne': 'Column label', 'Libellé': 'Label', 'cle': 'key', 'Clé': 'Key', 'Rôle': 'Role',
  'Type': 'Type', 'Table référencée': 'Referenced table', 'Table référencée…': 'Referenced table…', 'Long. max': 'Max length', 'Longueur maximale': 'Maximum length',
  'Oblig.': 'Req.', 'Détails': 'Details', 'Retirer': 'Remove', 'Libellé anglais': 'English label', 'Balise XML': 'XML tag',
  'Référence UNTDED / ISO 7372': 'UNTDED / ISO 7372 reference', 'Cardinalité': 'Cardinality', 'Unité de mesure': 'Unit of measure',
  'Format (expression régulière)': 'Format (regular expression)', 'Définition': 'Definition', 'Commentaire (règles de typologie…)': 'Comment (typology rules…)',
  'Ajouter une colonne': 'Add a column', 'Code (clé)': 'Code (key)', 'Libellé FR': 'French label', 'Libellé EN': 'English label', 'Attribut': 'Attribute',
  'Texte': 'Text', 'Texte long': 'Long text', 'Entier': 'Integer', 'Décimal': 'Decimal', 'Date': 'Date', 'Oui / non': 'Yes / no', 'Référence (table)': 'Reference (table)',

  // Métadonnées
  'Identité et classement': 'Identity and classification', 'complétude': 'completeness', 'Nom (FR)': 'Name (FR)', 'Nom (EN)': 'Name (EN)',
  'N° de fiche': 'Record no.', 'Catégorie': 'Category', 'Table parente (hiérarchie)': 'Parent table (hierarchy)', 'Aucune': 'None',
  'Elle-même (hiérarchie interne)': 'Itself (internal hierarchy)',
  'Modèle de référence de la chaîne logistique internationale (UN/CEFACT)': 'International Supply Chain Reference Model (UN/CEFACT)',
  'Identification': 'Identification', 'Cycle de vie du référentiel et qualité des données': 'Life cycle and data quality',
  'Métadonnées administratives, organismes associés': 'Administrative metadata, related organisations', 'à renseigner': 'to fill in',
  'Pièces jointes': 'Attachments', 'Pièce jointe': 'Attachment',
  'Documents de référence de la table : norme, recommandation UN/CEFACT, note de mise à jour, accord de partage… (20 Mo au plus par fichier).':
    'Reference documents of the table: standard, UN/CEFACT recommendation, update notice, sharing agreement… (20 MB maximum per file).',
  'Télécharger': 'Download', 'Aucune pièce jointe.': 'No attachment.', 'Déposez un fichier ou cliquez pour parcourir': 'Drop a file or click to browse',
  'Description (facultatif)': 'Description (optional)', 'Joindre': 'Attach', 'Métadonnées complémentaires': 'Additional metadata',
  "Ajoutez librement d'autres métadonnées (ex. « Accord de partage », « URL de la source », « Version UN/CEFACT »).":
    'Freely add other metadata (e.g. “Sharing agreement”, “Source URL”, “UN/CEFACT version”).',
  'nouvelleMetadonnee': 'newMetadata', 'Clé de la nouvelle métadonnée': 'Key of the new metadata', 'Créer la métadonnée': 'Create the metadata',
  "Fiche enregistrée. Les changements sont dans l'historique de la table.": "Record saved. The changes are in the table's history.",
  'Enregistrer la fiche': 'Save the record', 'Réactiver la table': 'Reactivate the table', 'Archiver la table': 'Archive the table',
  // Rubriques ISO 19115 (libellés fournis par l'API)
  'Description': 'Description', 'Langues': 'Languages', 'Utilisation (applications métiers utilisatrices)': 'Use (business applications)',
  'Standard de métadonnées': 'Metadata standard', 'Thématique': 'Theme', 'Jeu de caractères': 'Character set', 'Rôle ': 'Role',
  'Producteur-fournisseur / autorité de mise à jour': 'Producer-supplier / maintenance authority', 'Version': 'Version',
  'Point de diffusion': 'Distribution point', 'Mots clés descriptifs': 'Descriptive keywords', 'Date de création': 'Creation date',
  'Date de dernière mise à jour (révision)': 'Last update date (revision)', 'Fréquence de mise à jour': 'Update frequency',
  "Mode d'actualisation": 'Update mode', 'Règles de modification / modalités de maintenance': 'Change rules / maintenance arrangements',
  "Traçabilité de l'évolution dans le temps": 'Traceability over time', 'Règles de diffusion (ressource en ligne)': 'Distribution rules (online resource)',
  "Mode d'obtention": 'How to obtain', 'Auteurs de la dernière demande de mise à jour': 'Authors of the last update request',
  'Autorité nationale de mise à jour': 'National maintenance authority', "Restrictions d'usage": 'Use restrictions', "Contraintes d'accès": 'Access constraints',
  'Contact : producteur': 'Contact: producer', 'Contact : organisme de diffusion / administrateur': 'Contact: distributor / administrator',

  // Buy-Ship-Pay
  'Acheter': 'Buy', 'Expédier': 'Ship', 'Payer': 'Pay', 'Identifier les partenaires': 'Identify partners', "Établir l'accord commercial": 'Establish the business agreement',
  'Commander': 'Order', "Préparer l'exportation": 'Prepare for export', 'Exporter': 'Export', 'Transporter': 'Transport', "Préparer l'importation": 'Prepare for import',
  'Importer': 'Import', 'Facturer': 'Invoice', 'Rapprocher': 'Reconcile', 'Buy — acheter': 'Buy', 'Ship — expédier': 'Ship', 'Pay — payer': 'Pay',
  "Modèle de référence de la chaîne logistique internationale de l'UN/CEFACT : chaque table de référence est rattachée aux phases de la transaction commerciale où elle intervient.":
    'UN/CEFACT International Supply Chain Reference Model: each reference table is linked to the phases of the trade transaction in which it is used.',
  'Filtrer…': 'Filter…', 'vide': 'empty',

  // Import / export
  'Charger une liste de codes': 'Load a code list', 'cliquez pour changer': 'click to change', 'Déposez un fichier CSV, JSON ou Excel': 'Drop a CSV, JSON or Excel file',
  'ou cliquez pour parcourir': 'or click to browse', 'Fusion': 'Merge', 'Crée les nouveaux codes et met à jour les existants ; les autres restent inchangés.':
    'Creates new codes and updates existing ones; the others remain unchanged.', 'Remplacement': 'Replace',
  'Le fichier fait foi : les codes actifs absents sont invalidés (jamais supprimés).': 'The file prevails: active codes missing from it are invalidated (never deleted).',
  'Motif (historique)': 'Reason (history)', 'Ex. publication UN/CEFACT Rec. 21 Rév. 13': 'E.g. UN/CEFACT Rec. 21 Rev. 13 release',
  'Les colonnes sont reconnues par clé, libellé, balise XML ou « Libellé [clé] » ; une colonne absente du fichier laisse la valeur existante intacte. Codes, références et types sont contrôlés ligne par ligne.':
    'Columns are recognised by key, label, XML tag or “Label [key]”; a column missing from the file leaves the existing value untouched. Codes, references and types are checked line by line.',
  'Simuler': 'Simulate', 'Appliquer le chargement': 'Apply the load', "Simulation — rien n'a été enregistré": 'Simulation — nothing was saved',
  'Chargement appliqué · lot {0}': 'Load applied · batch {0}', 'lignes': 'lines', 'créés': 'created', 'modifiés': 'updated', 'inchangés': 'unchanged',
  'invalidés': 'invalidated', 'rejetés': 'rejected', 'Correspondance des colonnes': 'Column mapping',
  "Fichiers ré-importables. L'export Excel contient aussi la structure et la fiche ISO 19115.": 'Re-importable files. The Excel export also contains the structure and the ISO 19115 record.',
  'Codes exportés': 'Exported codes', 'Tous les codes': 'All codes', 'Codes actifs': 'Active codes', 'Codes invalidés': 'Invalidated codes',
  'Journal des chargements': 'Data load log', 'chargement initial': 'initial load', 'remplacement': 'replace', 'fusion': 'merge',
  'Aucun chargement pour cette table.': 'No load for this table.',

  // API & SQL
  'Services REST': 'REST services',
  "Interopérabilité (exigence e-Guce+_REF-07) : les autres services de la plateforme interrogent le référentiel par ces points d'accès.":
    'Interoperability (requirement e-Guce+_REF-07): the other platform services query the reference data through these endpoints.',
  'Copier': 'Copy', 'Liste des codes valides': 'Valid codes', 'Codes actifs à une date, libellés en fr ou en (listes déroulantes, contrôles de saisie).':
    'Active codes on a date, labels in fr or en (drop-down lists, input checks).', 'Vérifier un code': 'Check a code',
  'Renvoie le libellé si le code est valide, sinon 404.': 'Returns the label if the code is valid, otherwise 404.', 'Recherche paginée': 'Paged search',
  'Recherche sans accents, filtres de statut, de validité et de hiérarchie.': 'Accent-insensitive search, status, validity and hierarchy filters.',
  'Définition et métadonnées': 'Definition and metadata', 'Structure, fiche ISO 19115, phases Buy-Ship-Pay.': 'Structure, ISO 19115 record, Buy-Ship-Pay phases.',
  'Export': 'Export', 'csv, json ou xlsx — synchronisation des systèmes partenaires.': 'csv, json or xlsx — partner system synchronisation.',
  'Qui, quand, quoi, pourquoi, par quel canal.': 'Who, when, what, why, through which channel.', 'Table physique PostgreSQL': 'PostgreSQL physical table',
  "Vraie table, tenue à jour en temps réel, pour les autres systèmes et les extractions DataWarehouse / Big Data (exigence e-Guce+_REF-06) : colonnes typées nommées d'après les balises XML, clé primaire sur le code.":
    'Real table, updated in real time, for other systems and DataWarehouse / Big Data extractions (requirement e-Guce+_REF-06): typed columns named after the XML tags, primary key on the code.',
  'Dictionnaire de données': 'Data dictionary', 'Colonne SQL': 'SQL column',

  // Historique
  'Table (REF_…)': 'Table (REF_…)', 'Table': 'Table', 'Objet': 'Object', 'Tous objets': 'All objects', 'Tables': 'Tables', 'Colonnes': 'Columns',
  'Opération': 'Operation', 'Toutes opérations': 'All operations', 'Canal': 'Channel', 'Tous canaux': 'All channels', 'Interface': 'User interface',
  'Import': 'Import', 'Chargement initial': 'Initial load', 'SQL direct': 'Direct SQL', 'Synchronisation': 'Synchronisation', 'Auteur': 'Author',
  'Depuis le': 'Since', 'Création': 'Creation', 'Modification': 'Change', 'Invalidation': 'Invalidation', 'Réactivation': 'Reactivation',
  'Suppression': 'Deletion', 'Colonne': 'Column', 'lot {0}': 'batch {0}', "Aucun événement dans l'historique pour ces critères.": 'No history event for these criteria.',
  'Voir plus ({0} restants)': 'Show more ({0} remaining)', 'Nom EN': 'English name', 'Phases BSP': 'BSP phases', 'Longueur': 'Length',
  'Obligatoire': 'Required', 'Ordre': 'Order', 'Historique des modifications': 'Change history',
  "Toute création, modification, invalidation ou réactivation — par l'interface, l'API, un import ou un SQL direct — est tracée par la base de données avec son auteur, son canal, son motif et le détail avant / après.":
    'Every creation, change, invalidation or reactivation — through the user interface, the API, an import or direct SQL — is recorded by the database with its author, channel, reason and before / after details.',

  // Chargements
  'Chargements de listes de codes (fichiers, synchronisations) et chargement initial du Référentiel Commun.': 'Code list loads (files, synchronisations) and initial load of the Common Reference Data.',
  'Lot': 'Batch', 'Fichier': 'File', 'Mode': 'Mode', 'Lignes': 'Lines', 'Créés': 'Created', 'Modifiés': 'Updated', 'Rejetés': 'Rejected',
  'Initial': 'Initial', 'Aucun chargement.': 'No load.',

  // Nouvelle table
  'Nouvelle table de référence': 'New reference table',
  'La table est créée à chaud : structure, fiche ISO 19115, table physique et historique, sans redéploiement.': 'The table is created on the fly: structure, ISO 19115 record, physical table and history, with no redeployment.',
  'Point de départ': 'Starting point', 'Identité': 'Identity', 'Création ': 'Creation', 'Partir de zéro': 'Start from scratch',
  'Code, libellé FR, libellé EN : vous ajoutez ensuite vos attributs.': 'Code, French label, English label: then add your attributes.',
  "À partir d'un fichier": 'From a file',
  'Déposez un CSV, JSON ou Excel : NEXORA propose la structure (types, code, libellés) et détecte les colonnes qui référencent une table existante (pays, devises, unités…).':
    'Drop a CSV, JSON or Excel file: NEXORA proposes the structure (types, code, labels) and detects the columns referencing an existing table (countries, currencies, units…).',
  'Analyse en cours…': 'Analysing…', 'Structure proposée à partir de {0} ({1} lignes)': 'Structure proposed from {0} ({1} lines)',
  'références détectées': 'detected references', 'Code de la table': 'Table code',
  'Préfixe REF_ obligatoire ; devient aussi la table physique referentiel.{0}': 'REF_ prefix required; also becomes the physical table referentiel.{0}',
  'Choisir…': 'Choose…', 'Standards / références': 'Standards / references', 'Ex. UN/CEFACT Rec. 21 ; ISO 6346': 'E.g. UN/CEFACT Rec. 21; ISO 6346',
  'Phases Buy-Ship-Pay': 'Buy-Ship-Pay phases', 'Retour': 'Back', 'Aperçu du fichier ({0} premières lignes)': 'File preview (first {0} lines)',
  'Vérifier': 'Review', 'Récapitulatif': 'Summary', 'Classement': 'Classification', 'aucune phase': 'no phase',
  'Complexe ({0} attribut(s))': 'Complex ({0} attribute(s))', 'Simple (code / libellés)': 'Simple (code / labels)',
  'Charger aussi les {0} lignes de « {1} » après la création': 'Also load the {0} lines of “{1}” after creation',
  'Ex. nouvelle liste demandée par le MINCOMMERCE': 'E.g. new list requested by MINCOMMERCE', 'Créer la table': 'Create the table',
};
