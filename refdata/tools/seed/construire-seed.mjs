// Construit le jeu initial du Référentiel Commun NEXORA :
//   - catalogue.json : catégories, tables REF_*, colonnes et fiches de métadonnées ISO 19115
//     (source : RAPPORT_REFRENTIEL-COMMUN_GUCE_V1.5.docx) ;
//   - data/REF_*.jsonl.gz : contenu des tables (sources : Referentiel_UNCEFACT_Commerce_International_V1.0.xlsx,
//     NACAM_Nomenclature_des_activites_V1.0.xlsx).
// Usage : npm install && node construire-seed.mjs [dossier des sources]
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import AdmZip from 'adm-zip';
import { DOMParser } from '@xmldom/xmldom';
import XLSX from 'xlsx';

const ICI = path.dirname(fileURLToPath(import.meta.url));
const SOURCES = process.argv[2] ?? process.env.REFDATA_SOURCES
  ?? 'D:/DEV_2026/JAMEL_2026/ElKamelEngineering_2026/0MVP_GUCE/REFDATA';
const SORTIE = path.resolve(ICI, '../../backend/src/main/resources/seed');
const RAPPORT = 'RAPPORT_REFRENTIEL-COMMUN_GUCE_V1.5.docx';
const CLASSEUR_CEFACT = 'Referentiel_UNCEFACT_Commerce_International_V1.0.xlsx';
const CLASSEUR_NACAM = 'NACAM_Nomenclature_des_activites_V1.0.xlsx';

// ---------------------------------------------------------------------------------------------
// Catégories : structure du rapport (II.1 à II.4) ; la source suit ERMIS/NSID (Internationale,
// Régionale, Nationale).
// ---------------------------------------------------------------------------------------------
const CATEGORIES = [
  { code: 'INT', parent: null, labelFr: 'Standards internationaux', labelEn: 'International standards', source: 'INTERNATIONALE', icon: 'globe', color: '#1565E0', order: 1,
    description: 'Listes de codes issues des normes ISO, des recommandations UN/CEFACT, de l\'OMD et de l\'OMI.' },
  { code: 'REG', parent: null, labelFr: 'Normes régionales (CEMAC)', labelEn: 'Regional standards (CEMAC)', source: 'REGIONALE', icon: 'flag', color: '#19A7C9', order: 2,
    description: 'Codifications communautaires CEMAC : régimes douaniers, codes additionnels, titres de transport…' },
  { code: 'NAT', parent: null, labelFr: 'Tables nationales', labelEn: 'National tables', source: 'NATIONALE', icon: 'landmark', color: '#2E9E5B', order: 3,
    description: 'Référentiels nationaux : opérateurs, découpage administratif, banques, activités (NACAM)…' },
  { code: 'DOM', parent: null, labelFr: 'Domaines d\'activité', labelEn: 'Business domains', source: 'NATIONALE', icon: 'layers', color: '#7AC943', order: 4,
    description: 'Tables spécifiques aux procédures métier du commerce extérieur.' },
  { code: 'DOM_DOUANE', parent: 'DOM', labelFr: 'Procédures de dédouanement', labelEn: 'Customs clearance', source: 'NATIONALE', icon: 'stamp', color: '#0B2A6F', order: 1 },
  { code: 'DOM_TRANSPORT', parent: 'DOM', labelFr: 'Transport', labelEn: 'Transport', source: 'NATIONALE', icon: 'ship', color: '#1565E0', order: 2 },
  { code: 'DOM_CONTROLE', parent: 'DOM', labelFr: 'Contrôle technique', labelEn: 'Technical control', source: 'NATIONALE', icon: 'microscope', color: '#19A7C9', order: 3 },
  { code: 'DOM_CACAO_CAFE', parent: 'DOM', labelFr: 'Export cacao-café', labelEn: 'Cocoa-coffee export', source: 'NATIONALE', icon: 'leaf', color: '#2E9E5B', order: 4 },
  { code: 'DOM_VEHICULES', parent: 'DOM', labelFr: 'Dédouanement des véhicules', labelEn: 'Vehicle clearance', source: 'NATIONALE', icon: 'car', color: '#7AC943', order: 5 },
];

const categorieDuNumero = (num, rubrique) => {
  const [chapitre] = num.split('.');
  return { 1: 'INT', 2: 'REG', 3: 'NAT', 4: 'DOM_DOUANE', 5: 'DOM_TRANSPORT', 6: 'DOM_CONTROLE', 7: 'DOM_CACAO_CAFE', 8: 'DOM_VEHICULES' }[chapitre]
    ?? (rubrique ?? 'DOM');
};

// Noms canoniques : ceux du classeur UN/CEFACT quand la table y figure, sinon REP_ du rapport → REF_.
const ALIAS = {
  REP_INCOTERMS: 'REF_INCOTERM', REP_MODE_TRANSPORT: 'REF_TRANSPORT_MODE', REP_MODES_TRANSPORT: 'REF_TRANSPORT_MODE',
  REP_TYPES_MOYEN_TRANSPORT: 'REF_TRANSPORT_MEANS', REP_TYPES_PACKAGE: 'REF_PACKAGING_TYPES', REP_TYPE_PACKAGE: 'REF_PACKAGING_TYPES',
  REP_KIND_PACKAGE: 'REF_PACKAGING_CATEGORIES', REP_KIND_MATERIAL: 'REF_PACKAGING_MATERIALS',
  REP_KIND_PACKAGE_DANGEROUS_GOOD: 'REF_PACKAGING_DANGEROUS_GOODS_CATEGORIES',
  REP_KIND_MATERIAL_DANGEROUS_GOOD: 'REF_PACKAGING_DANGEROUS_GOODS_MATERIALS',
  REP_TYPES_PACKAGE_DANGEROUS_GOOD: 'REF_PACKAGING_DANGEROUS_GOODS_TYPES', REP_TYPE_PACKAGE_DANGEROUS_GOODS: 'REF_PACKAGING_DANGEROUS_GOODS_TYPES',
  REP_UNIT_MEASURE: 'REF_MEASUREMENT_UNIT', REP_UIT_MEASURE_CEMAC: 'REF_MEASUREMENT_UNIT_CEMAC',
  REP_ATTACHED_DOCUMENTS: 'REF_DOCUMENT_CODES', REP_FREIGHT_COST: 'REF_TRANSPORT_FCC',
  REP_TRADE_TRANSPORT_STATUS: 'REF_BSP_STATUS', REP_ACTIVITY: 'REF_NACAM', REP_SUBDIVISION: 'REF_COUNTRY_SUBDIVISION',
  REP_CUSTOMES_REGIME: 'REF_CUSTOMS_REGIME', REP_CUSTOMES_OFFICE: 'REF_CUSTOMS_OFFICE', REP_RECHNICAL_SERVICE: 'REF_TECHNICAL_SERVICE',
  REP_GARDE_CAFE_ROBUSTA: 'REF_GRADE_CAFE_ROBUSTA', REP_TYPE_CUSTOMES_INFRACTION: 'REF_TYPE_CUSTOMS_INFRACTION',
};
const canonique = rep => {
  const r = rep.replace(/\s+/g, '').toUpperCase();
  return ALIAS[r] ?? r.replace(/^REP_/, 'REF_');
};

// Modèle de référence de la chaîne logistique internationale (UN/CEFACT ISCRM) : Buy – Ship – Pay.
function phasesBsp(code) {
  const p = new Set();
  if (/CURRENCY|PAYTERM|PAYMENT|BANK|FCC|FREIGHT/.test(code)) p.add('PAY');
  if (/COUNTRY|CURRENCY|INCOTERM|NDP|MEASUREMENT|UNIT|DOCUMENT|NACAM|ACTIVITY|NIU|PATENTE|FIMEX|RCCM|TRADE_TRANSACTION|LANGUAGE|PAYTERM|ESSENCE|MED|CAFE|CACAO|PRODUI|PRODUCT|ESPECE|NATURE|QUALITE|VEHICLE|EXPORTATEUR|TRANSFORMATEUR|USINIER|ADDITIONAL/.test(code)) p.add('BUY');
  if (!/PAYTERM|PAYMENT|BANK|^REF_NACAM|NIU|PATENTE|RCCM|LANGUAGE/.test(code) || /FCC|FREIGHT/.test(code)) p.add('SHIP');
  if (p.size === 0) p.add('SHIP');
  return ['BUY', 'SHIP', 'PAY'].filter(x => p.has(x));
}

// ---------------------------------------------------------------------------------------------
// Lecture du rapport Word
// ---------------------------------------------------------------------------------------------
function texte(noeud) {
  let s = '';
  const parcourir = n => {
    for (const c of Array.from(n.childNodes ?? [])) {
      if (c.nodeName === 'w:t') s += c.textContent;
      else if (c.nodeName === 'w:tab') s += ' ';
      else if (c.nodeName === 'w:br') s += '\n';
      else if (c.nodeName === 'w:p') { parcourir(c); if (s && !s.endsWith('\n')) s += '\n'; }
      else parcourir(c);
    }
  };
  parcourir(noeud);
  return s.split('\n').map(l => l.replace(/\s+/g, ' ').trim()).filter(Boolean).join('\n');
}
const enfants = (n, nom) => Array.from(n.childNodes ?? []).filter(c => c.nodeName === nom);

function lireDocx(fichier) {
  const xml = new AdmZip(fichier).readAsText('word/document.xml');
  const body = new DOMParser().parseFromString(xml, 'text/xml').getElementsByTagName('w:body')[0];
  const blocs = [];
  for (const n of Array.from(body.childNodes)) {
    if (n.nodeName === 'w:p') { const t = texte(n); if (t) blocs.push({ type: 'p', texte: t }); }
    else if (n.nodeName === 'w:tbl') blocs.push({ type: 't', lignes: enfants(n, 'w:tr').map(tr => enfants(tr, 'w:tc').map(texte)) });
  }
  return blocs;
}

const sansAccent = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[’']/g, "'").toLowerCase();
const uneLigne = s => (s ?? '').replace(/\s*\n\s*/g, ' ').trim();
// Le rapport écrit ses titres en majuscules non accentuées : restitution des accents usuels.
const ACCENTS = {
  unites: 'unités', unite: 'unité', activites: 'activités', echanges: 'échanges', materiaux: 'matériaux', materaux: 'matériaux',
  regimes: 'régimes', especes: 'espèces', espece: 'espèce', therapeutiques: 'thérapeutiques', medicaments: 'médicaments',
  homologues: 'homologués', specialites: 'spécialités', lies: 'liés', forestieres: 'forestières', forestiers: 'forestiers',
  surete: 'sûreté', vehicules: 'véhicules', vehicule: 'véhicule', categories: 'catégories', categorie: 'catégorie',
  decoupage: 'découpage', operateurs: 'opérateurs', exterieur: 'extérieur', proprietaires: 'propriétaires', modeles: 'modèles',
  numeros: 'numéros', agrees: 'agréés', agreements: 'agréments', agrements: 'agréments', securite: 'sécurité',
  methodes: 'méthodes', preparation: 'préparation', qualite: 'qualité', reglementation: 'réglementation', douanieres: 'douanières',
  geographiques: 'géographiques', aeroports: 'aéroports', dangereuses: 'dangereuses', departements: 'départements',
  regions: 'régions', procedures: 'procédures', prelevement: 'prélèvement', energie: 'énergie', resume: 'résumé',
  stockges: 'stockage', stockages: 'stockage', traitements: 'traitements', sylvicoles: 'sylvicoles', miniers: 'miniers',
  bassins: 'bassins', declarants: 'déclarants', entrepots: 'entrepôts', magasins: 'magasins', agrement: 'agrément',
  additionnels: 'additionnels', infractions: 'infractions', remplissage: 'remplissage', scellement: 'scellement',
  botaniques: 'botaniques', granulometrique: 'granulométrique', origine: 'origine', halieutique: 'halieutique',
  conservateurs: 'conservateurs', equipement: 'équipement', equipements: 'équipements', cargaison: 'cargaison',
  carte: 'carte', grises: 'grises', visite: 'visite', technique: 'technique', techniques: 'techniques', nomenclature: 'nomenclature',
  harmonise: 'harmonisé', systeme: 'système', fret: 'fret', moyen: 'moyen', moyens: 'moyens', pavillon: 'pavillon',
  delivrance: 'délivrance', titres: 'titres', exploitation: 'exploitation', autres: 'autres', zones: 'zones', etat: 'état',
  cafe: 'café', cafes: 'cafés', qualites: 'qualités', methode: 'méthode', methodes2: 'méthodes', matieres: 'matières',
  repertoire: 'répertoire', forestiere: 'forestière', dedouanement: 'dédouanement', addtionnels: 'additionnels',
  coduire: 'conduire', a: 'à', titre: 'titre', energie2: 'énergie',
};
const ACRONYMES = new Set(['SH', 'ISO', 'OMI', 'OMD', 'CEMAC', 'UN', 'UNDG', 'NIU', 'LVO', 'NST', 'ISPS', 'NACAM', 'NAEMA', 'ICO', 'ICCO', 'UN/LOCODE', 'EDIFACT', 'BEAC', 'SYDONIA', 'NSH', 'CITI', 'FCC', 'PAYTERMS', 'IATA']);
function phraseCase(s) {
  const mots = uneLigne(s).toLowerCase().split(/(\s+|\(|\)|,|-|’|')/);
  let premier = true;
  return mots.map(m => {
    const maj = m.toUpperCase();
    if (ACRONYMES.has(maj)) { premier = false; return maj; }
    const accentue = ACCENTS[m] ?? m;
    if (premier && /\p{L}/u.test(accentue)) { premier = false; return accentue.charAt(0).toUpperCase() + accentue.slice(1); }
    return accentue;
  }).join('').replace(/\s+/g, ' ').trim();
}

const CLES_FICHE = [
  ['contact : producteur', 'producerContact'], ['contact : organisme', 'administratorContact'],
  ['libelle court', 'shortLabel'], ['langues', 'languages'], ['description', 'description'], ['standard de metadonnees', 'metadataStandard'],
  ['standards', 'standards'], ['utilisation', 'usage'], ['thematique', 'theme'], ['jeu de caracteres', 'charset'],
  ['role', 'role'], ['producteur', 'producer'], ['version', 'version'], ['point de diffusion', 'diffusionPoint'],
  ['mots cle', 'keywords'], ['date de creation', 'creationDate'], ['date derniere mise a jour', 'revisionDate'],
  ['frequence', 'updateFrequency'], ['regles de modification', 'maintenanceRules'], ['tracabilite', 'traceability'],
  ['regles de diffusion', 'diffusionRules'], ['mode d', 'obtentionMode'], ['auteurs', 'lastRequestAuthors'],
  ['restrictions', 'useRestrictions'], ['contraintes', 'accessConstraints'],
];
const cleFiche = libelle => CLES_FICHE.find(([k]) => sansAccent(libelle).startsWith(k))?.[1];

function analyserRapport(blocs) {
  const inventaire = new Map();
  const fiches = new Map();
  const definitions = [];
  let rubrique = null, ficheCourante = null, defCourante = null;

  for (const b of blocs.filter(b => b.type === 't')) {
    const entete = b.lignes[0] ?? [];
    // Inventaires des sections II.1 à II.4
    if (entete[0] === 'N°' && entete.some(c => /Table de R/i.test(c))) {
      const idx = re => entete.findIndex(c => re.test(sansAccent(c)));
      const iStd = idx(/standard|description/), iProd = idx(/producteur/), iAut = idx(/autorite/),
        iObt = idx(/obtention/), iAct = idx(/actualisation/);
      for (const l of b.lignes.slice(1)) {
        if (l.length === 1) { rubrique = sansAccent(l[0]).includes('vehicule') ? 'DOM_VEHICULES' : sansAccent(l[0]).includes('cacao') ? 'DOM_CACAO_CAFE' : rubrique; continue; }
        if (!/^\d\.\d{2}$/.test(l[0])) continue;
        let num = l[0];
        if (rubrique === 'DOM_VEHICULES') num = '8.' + num.split('.')[1];
        inventaire.set(num, {
          num, titre: uneLigne(l[1]), standards: uneLigne(l[iStd]), producteur: uneLigne(l[iProd]), autorite: uneLigne(l[iAut]),
          obtention: iObt >= 0 && iAct >= 0 ? uneLigne(l[iObt]) : null,
          actualisation: uneLigne(l[iAct >= 0 ? iAct : iObt]),
        });
      }
      continue;
    }
    for (const l of b.lignes) {
      // Début de fiche : « 1.02- CODES PAYS »
      const mf = l.length === 1 && /^(\d\.\d{2})\s*[-–]\s*(.+)$/s.exec(uneLigne(l[0]));
      if (mf) {
        let num = mf[1];
        if (num.startsWith('7.') && /v[eé]hicul|carburant|energie/i.test(sansAccent(mf[2]))) num = '8.' + num.split('.')[1];
        ficheCourante = { num, titre: uneLigne(mf[2]), meta: {} };
        fiches.set(num, ficheCourante);
        defCourante = null;
        continue;
      }
      // Paires clé / valeur de la fiche ISO 19115
      if (ficheCourante && (l.length === 2 || l.length === 4) && !/\[REP_/.test(l[0])) {
        for (let i = 0; i + 1 < l.length; i += 2) {
          const cle = cleFiche(l[i]);
          if (cle && l[i + 1] && !ficheCourante.meta[cle]) ficheCourante.meta[cle] = l[i + 1];
        }
      }
      // En-tête d'une structure : « Pays [REP_COUNTRY] »
      const mr = /\[\s*(REP_[A-Z0-9_ ]+?)\s*\]/.exec(l[0] ?? '');
      if (mr && !/^libell/i.test(sansAccent(l[0])) && l.length >= 2) {
        const rep = mr[1].replace(/\s+/g, '');
        defCourante = { rep, code: canonique(rep), libelle: uneLigne(l[0].replace(mr[0], '')), fiche: ficheCourante?.num, colonnes: [] };
        definitions.push(defCourante);
        continue;
      }
      // Attribut : NOM | CARDINALITE | Balise XML | TYPE..LONGUEUR | DEFINITION | UNITE | UNTDED | COMMENTAIRES
      if (defCourante && l.length >= 7 && /^\(\s*\d\s*,\s*[\dn]\s*\)$/i.test(l[1]) && l[0] && !/^nom$/i.test(l[0])) {
        defCourante.colonnes.push({
          libelle: uneLigne(l[0]), cardinalite: l[1].replace(/\s/g, ''), balise: uneLigne(l[2]).replace(/[<>\s]/g, ''),
          format: uneLigne(l[3]), definition: uneLigne(l[4]), unite: uneLigne(l[5]), untded: uneLigne(l[6]), commentaire: uneLigne(l[7]),
        });
      }
    }
  }
  return { inventaire, fiches, definitions };
}

// ---------------------------------------------------------------------------------------------
// Construction des colonnes (structure des tables)
// ---------------------------------------------------------------------------------------------
const camel = s => sansAccent(s).replace(/[^a-z0-9]+(.)/g, (_, c) => c.toUpperCase()).replace(/[^a-zA-Z0-9]/g, '').replace(/^./, c => c.toLowerCase());
const slugCle = libelle => camel(libelle) || 'attribut';

function colonneDepuisRapport(c, index) {
  const fk = /foreign\s*key\s*\[?\s*(REP_[A-Z0-9_ ]+)/i.exec(c.definition);
  const f = /^(an|a|n)\s*(?:\.\.|\.)?\s*(\d+)?(?:\s*[,.]\s*(\d+))?/i.exec(c.format.replace(/\s/g, '')) ?? [];
  const nature = (f[1] ?? 'an').toLowerCase();
  const longueur = f[2] ? Number(f[2]) : null;
  let type = 'STRING';
  if (fk) type = 'CODE_REF';
  else if (/^date\b/i.test(sansAccent(c.libelle)) && !(longueur && longueur <= 4)) type = 'DATE';
  else if (nature === 'n' && (f[3] || /poids|montant|superficie|quantit|taux|valeur|capacit|longueur|largeur|tonnage|nombre|volume/.test(sansAccent(c.libelle)))) type = 'DECIMAL';
  return {
    key: c.balise || slugCle(c.libelle), labelFr: c.libelle, dataType: type, maxLength: type === 'STRING' || type === 'CODE_REF' ? longueur : null,
    pattern: type === 'STRING' && nature === 'n' ? '^[0-9]*$' : null,
    required: c.cardinalite.startsWith('(1'), cardinality: c.cardinalite, xmlTag: c.balise || null, untded: c.untded || null,
    unit: c.unite || null, definition: c.definition && !/^(primary|foreign)/i.test(c.definition) ? c.definition : null,
    comment: c.commentaire || null, refTable: fk ? canonique(fk[1]) : null, primaryKey: /primary\s*key/i.test(c.definition), order: index + 1,
  };
}

/** Attribue les rôles CODE / LABEL_FR / LABEL_EN / PARENT aux colonnes, le reste étant des attributs. */
function attribuerRoles(colonnes) {
  colonnes.forEach(c => { c.role = 'ATTRIBUTE'; });
  const code = colonnes.find(c => c.primaryKey) ?? colonnes[0];
  if (code) { code.role = 'CODE'; code.required = true; if (code.dataType === 'CODE_REF') { code.dataType = 'STRING'; } }
  const libre = c => c.role === 'ATTRIBUTE' && (c.dataType === 'STRING');
  const fr = colonnes.find(c => libre(c) && /\(fr\)/i.test(c.labelFr))
    ?? colonnes.find(c => libre(c) && !/\(en\)/i.test(c.labelFr) && /nom|libell|descr|design|appellation|intitul|denomination/.test(sansAccent(c.labelFr)));
  if (fr) fr.role = 'LABEL_FR';
  const en = colonnes.find(c => libre(c) && /\(en\)/i.test(c.labelFr));
  if (en) { en.role = 'LABEL_EN'; en.required = false; }
  colonnes.forEach(c => { delete c.primaryKey; });
  return colonnes;
}

function colonnesSimples(prefixeLibelle) {
  return [
    { key: 'code', labelFr: 'Code', dataType: 'STRING', maxLength: 35, required: true, role: 'CODE', order: 1 },
    { key: 'labelFr', labelFr: `${prefixeLibelle} (FR)`, dataType: 'STRING', maxLength: 512, required: true, role: 'LABEL_FR', order: 2 },
    { key: 'labelEn', labelFr: `${prefixeLibelle} (EN)`, dataType: 'STRING', maxLength: 512, required: false, role: 'LABEL_EN', order: 3 },
  ];
}

// ---------------------------------------------------------------------------------------------
// Lecture générique des classeurs : chaque section commence par « Table : | REF_xxx »
// ---------------------------------------------------------------------------------------------
function lireSections(fichier) {
  const wb = XLSX.readFile(fichier);
  const sections = [];
  for (const nom of wb.SheetNames) {
    const lignes = XLSX.utils.sheet_to_json(wb.Sheets[nom], { header: 1, defval: '', blankrows: true, raw: false })
      .map(r => r.map(v => String(v).trim()));
    for (let i = 0; i < lignes.length; i++) {
      const r = lignes[i];
      const iTable = r.findIndex(v => /^table\s*:?$/i.test(v));
      const ref = r.find(v => /^REF_[A-Z0-9_]+$/.test(v)) ?? (r.some(v => /Table\s*:\s*REF_/.test(v)) ? /REF_[A-Z0-9_]+/.exec(r.join(' '))[0] : null);
      if ((iTable < 0 && !r.some(v => /Table\s*:\s*REF_/.test(v))) || !ref) continue;
      // En-tête : première ligne suivante avec au moins deux cellules renseignées
      let j = i + 1;
      while (j < lignes.length && lignes[j].filter(Boolean).length < 2) j++;
      const entete = lignes[j] ?? [];
      const donnees = [];
      for (let k = j + 1; k < lignes.length; k++) {
        const l = lignes[k];
        if (l.some(v => /^table\s*:?/i.test(v) && l.some(w => /REF_/.test(w)))) break;
        if (!l.filter(Boolean).length) { if (donnees.length) break; continue; }
        if (l.filter(Boolean).length === 1 && donnees.length) break; // note de bas de tableau
        donnees.push(Object.fromEntries(entete.map((h, x) => [h, l[x] ?? ''])));
      }
      sections.push({ feuille: nom, ref: ref === 'REF_TRANSPORT_MEAMNS' ? 'REF_TRANSPORT_MEANS' : ref, entete: entete.filter(Boolean), donnees });
      i = j;
    }
  }
  return sections;
}

// Correspondance colonnes du classeur → rôles et balises (alignées sur le rapport quand la table y est décrite).
const CORRESPONDANCES = {
  REF_COUNTRY: { code: 'Code Alpha', fr: 'Libellé Pays', attrs: { 'Code numérique': 'CountryIdNum' } },
  REF_CURRENCY_ENTITY: { code: r => `${r['Code Pays (Alpha-2)'] || r['Entité (pays/territoire)']}-${r['Code Alphabétique']}`, fr: 'Entité (pays/territoire)',
    attrs: { 'Code Pays (Alpha-2)': 'CountryIdAlpha2', 'Devise': 'CurrencyName', 'Code Alphabétique': 'CurrencyCode', 'Code Numérique': 'CurrencyNumericCode', 'Décimales': 'CurrencyMinorUnit' } },
  REF_INCOTERM: { code: 'Code', fr: 'Description (français)', en: 'Libellé (anglais)', attrs: { 'Catégorie (Incoterms® 2020)': 'IncotermCategory' } },
  REF_UNLOCODE_FUNCTION: { code: 'Code', fr: 'Fonction', attrs: { 'Définition': 'description' } },
  REF_UNLOCODE: { code: 'Code LOCODE', fr: 'Nom du lieu',
    attrs: { 'Code Pays (Alpha-2)': 'CountryIdAlpha2', 'Code Lieu': 'LocationPlaceCode', 'Fonctions_Codes': 'LocationFunctionCode', 'Fonctions': 'LocationFunctions', 'Port': 'IsPort', 'Aéroport': 'IsAirport' } },
  REF_TRANSPORT_MODE: { code: 'Code', fr: 'Désignation', attrs: { 'Description': 'description' } },
  // Rec. 28 : les codes ne sont uniques qu'au sein d'un mode de transport → code composé « mode-code ».
  REF_TRANSPORT_MEANS: { code: r => `${r['Mode']}-${r['Code']}`, fr: 'Désignation',
    parent: r => r['Code'].includes('.') ? `${r['Mode']}-${r['Code'].split('.')[0]}` : '',
    attrs: { 'Mode': 'ModeTransportCode', 'Code': 'TypeMeanTransportCode', 'Description': 'description' } },
  REF_PAYTERMS_GROUP: { code: 'Groupe', fr: 'Libellé du groupe', attrs: { 'Nombre de codes PAYTERMS': 'codeCount', 'Codes associés': 'payTermCodes' } },
  REF_PAYTERMS: { code: 'Code', fr: 'Libellé', parent: 'Groupe', attrs: { 'Description': 'description' } },
  REF_PACKAGING_TYPES: { code: 'Code', fr: 'Désignation', attrs: { 'Description': 'description' } },
  REF_MEASUREMENT_UNIT_CATEGORIES: { code: 'Code', fr: 'Catégorie', attrs: { 'Description': 'description', 'Norme de référence': 'isoStandard' } },
  REF_MEASUREMENT_UNIT: { code: 'Code', fr: 'Désignation', attrs: { 'Code Catégorie': 'UnitMeasureCategory', 'Symbole': 'UnitMeasureSymbol', 'Description': 'UnitMeasureQuantity' } },
  REF_TRANSPORT_FCC_GROUP: { code: 'Groupe', fr: 'Libellé du groupe' },
  REF_TRANSPORT_FCC_SUBGROUP: { code: r => `${r['Groupe']}.${r['Sous-groupe']}`, fr: 'Libellé du sous-groupe', parent: 'Groupe', attrs: { 'Sous-groupe': 'subgroup' } },
  REF_TRANSPORT_FCC: { code: 'Code', fr: 'Description', parent: r => `${r['Groupe']}.${r['Sous-groupe']}`, attrs: { 'Groupe': 'group', 'Sous-groupe': 'subgroup' } },
  REF_BSP_STATUS: { code: 'Code', fr: 'Désignation', attrs: { 'Description': 'description' } },
  REF_DOCUMENT_CODES: { code: 'Code (UNCL 1001)', fr: 'Document (français)', en: 'Document (anglais)' },
  REF_NACAM_SECTIONS: { code: 'Section', fr: 'Libellé section',
    attrs: { 'Branches NACAM Rév.1': 'nacamBranches', 'Divisions NAEMA / CITI Rév.4': 'naemaDivisions', 'Nb de branches': 'branchCount', 'Nb de classes': 'classCount' } },
  REF_NACAM_BRANCHES: { code: 'Code branche', fr: 'Libellé branche', parent: 'Section',
    attrs: { 'Ancien code NACAM': 'oldNacamCode', 'NAEMA Rév.1': 'naemaCode', 'CITI Rév.4': 'isicCode', 'Nb de classes': 'classCount' } },
  REF_NACAM: { code: 'Code classe', fr: 'Libellé classe', parent: 'Code branche',
    attrs: { 'Code Section': 'sectionCode', 'Ancien code NACAM': 'oldNacamCode', 'NAEMA Rév.1': 'naemaCode', 'CITI Rév.4': 'isicCode' } },
};
const RENOMMAGE_SECTIONS = { REF_GROUP_PAYTERMS: 'REF_PAYTERMS_GROUP', REF_STRUCTURE_UNLOCODE: 'REF_UNLOCODE_FUNCTION' };

// Tables propres au classeur : rattachement à une fiche du rapport (métadonnées héritées) et description.
const TABLES_CLASSEUR = {
  REF_CURRENCY_ENTITY: { fiche: '1.03', nom: 'Devises par pays/entité', desc: 'Correspondance ISO 4217 entre entités (pays, territoires) et monnaies, y compris fonds spéciaux et métaux précieux.', parentTable: null },
  REF_UNLOCODE_FUNCTION: { fiche: '1.09', nom: 'Fonctions UN/LOCODE', desc: 'Codes des attributs de fonction des lieux UN/LOCODE (Rec. 16).' },
  REF_PAYTERMS_GROUP: { fiche: '1.15', nom: 'Groupes PAYTERMS', desc: 'Regroupement des abréviations PAYTERMS (Rec. 17).' },
  REF_MEASUREMENT_UNIT_CATEGORIES: { fiche: '1.14', nom: 'Catégories d\'unités de mesure', desc: 'Catégories des unités de mesure (Rec. 20) rattachées à l\'ISO/IEC 80000.' },
  REF_TRANSPORT_FCC_GROUP: { fiche: '1.16', nom: 'Groupes de frais de transport', desc: 'Groupes des codes de frais de transport (Rec. 23).' },
  REF_TRANSPORT_FCC_SUBGROUP: { fiche: '1.16', nom: 'Sous-groupes de frais de transport', desc: 'Sous-groupes des codes de frais de transport (Rec. 23).' },
  REF_PACKAGING_DANGEROUS_GOODS_CATEGORIES: { fiche: '1.11', nom: 'Genres d\'emballages (marchandises dangereuses)', desc: 'Codes des genres d\'emballages pour les marchandises dangereuses (Rec. 21).' },
  REF_PACKAGING_DANGEROUS_GOODS_MATERIALS: { fiche: '1.11', nom: 'Matériaux d\'emballage (marchandises dangereuses)', desc: 'Codes des matériaux d\'emballage pour les marchandises dangereuses (Rec. 21).' },
  REF_NACAM_SECTIONS: { fiche: '3.12', nom: 'Sections NACAM', desc: 'Sections de la Nomenclature des Activités du Cameroun (NACAM Rév.1), alignées sur la CITI Rév.4.' },
  REF_NACAM_BRANCHES: { fiche: '3.12', nom: 'Branches NACAM', desc: 'Branches de la NACAM Rév.1 avec correspondances NAEMA et CITI.' },
};

// Noms d'affichage (FR, EN) des principales tables.
const NOMS = {
  REF_NDP: ['Nomenclature douanière des produits (SH)', 'Customs tariff nomenclature (HS)'], REF_NDP_CHAPTER: ['Chapitres du Système harmonisé', 'HS chapters'],
  REF_COUNTRY: ['Codes pays (ISO 3166-1)', 'Country codes (ISO 3166-1)'], REF_COUNTRY_SUBDIVISION: ['Subdivisions de pays (ISO 3166-2)', 'Country subdivisions (ISO 3166-2)'],
  REF_LANGUAGE: ['Langues (ISO 639)', 'Languages (ISO 639)'], REF_CURRENCY: ['Codes devises (ISO 4217)', 'Currency codes (ISO 4217)'],
  REF_CURRENCY_ENTITY: ['Devises par pays/entité (ISO 4217)', 'Currencies by entity (ISO 4217)'], REF_INCOTERM: ['Incoterms® 2020', 'Incoterms® 2020'],
  REF_TRANSPORT_MODE: ['Modes de transport', 'Modes of transport'], REF_TRANSPORT_MEANS: ['Types de moyens de transport', 'Types of means of transport'],
  REF_FLAG_MEAN_TRANSPORT: ['Pavillons des moyens de transport', 'Flags of means of transport'],
  REF_UNLOCODE: ['UN/LOCODE — ports, aéroports et lieux', 'UN/LOCODE — ports, airports and locations'], REF_UNLOCODE_FUNCTION: ['Fonctions UN/LOCODE', 'UN/LOCODE functions'],
  REF_VESSEL: ['Navires (numéros OMI)', 'Vessels (IMO numbers)'],
  REF_PACKAGING_TYPES: ['Types d\'emballages', 'Package types'], REF_PACKAGING_CATEGORIES: ['Genres d\'emballages', 'Package kinds'],
  REF_PACKAGING_MATERIALS: ['Matériaux d\'emballage', 'Packaging materials'],
  REF_PACKAGING_DANGEROUS_GOODS_TYPES: ['Types d\'emballages (marchandises dangereuses)', 'Package types (dangerous goods)'],
  REF_PACKAGING_DANGEROUS_GOODS_CATEGORIES: ['Genres d\'emballages (marchandises dangereuses)', 'Package kinds (dangerous goods)'],
  REF_PACKAGING_DANGEROUS_GOODS_MATERIALS: ['Matériaux d\'emballage (marchandises dangereuses)', 'Packaging materials (dangerous goods)'],
  REF_TYPE_EQUIPMENT: ['Types d\'équipements', 'Equipment types'], REF_SIZE_EQUIPMENT: ['Tailles d\'équipements (ISO 6346)', 'Equipment sizes (ISO 6346)'],
  REF_MEASUREMENT_UNIT: ['Unités de mesure', 'Units of measure'], REF_MEASUREMENT_UNIT_CATEGORIES: ['Catégories d\'unités de mesure', 'Unit of measure categories'],
  REF_PAYTERMS: ['Conditions de paiement (PAYTERMS)', 'Payment terms (PAYTERMS)'], REF_PAYTERMS_GROUP: ['Groupes PAYTERMS', 'PAYTERMS groups'],
  REF_TRANSPORT_FCC: ['Codes des frais de transport (FCC)', 'Freight cost codes (FCC)'],
  REF_BSP_STATUS: ['Statuts du commerce et du transport', 'Trade and transport status codes'],
  REF_DOCUMENT_CODES: ['Codes des documents', 'Document codes'], REF_NACAM: ['Classes d\'activités (NACAM)', 'Activity classes (NACAM)'],
  REF_NACAM_SECTIONS: ['Sections NACAM', 'NACAM sections'], REF_NACAM_BRANCHES: ['Branches NACAM', 'NACAM branches'],
  REF_CUSTOMS_REGIME: ['Régimes douaniers', 'Customs procedures'], REF_CUSTOMS_OFFICE: ['Bureaux de douane', 'Customs offices'],
  REF_ADMIN_REGION: ['Régions', 'Regions'], REF_ADMIN_DEPT: ['Départements', 'Divisions'], REF_ADMIN_ARRDST: ['Arrondissements', 'Subdivisions'],
  REF_ADMIN_LOCALITE: ['Localités', 'Localities'], REF_ADMIN_VILLAGE: ['Villages', 'Villages'], REF_BANK: ['Banques', 'Banks'],
  REF_BANK_AGENCY: ['Agences bancaires', 'Bank branches'], REF_VEHICLE_MARK: ['Marques de véhicules', 'Vehicle makes'],
  REF_VEHICLE_MODEL: ['Modèles de véhicules', 'Vehicle models'], REF_VEHICLE_ENERGY: ['Types de carburant (sources d\'énergie)', 'Fuel types'],
  REF_ADDITIONAL_CODES: ['Codes additionnels de dédouanement', 'Additional customs codes'], REF_DRIVING_LICENCE: ['Permis de conduire', 'Driving licences'],
};

// Hiérarchies et clés étrangères ajoutées aux structures du rapport.
const PARENTS = {
  REF_TRANSPORT_MEANS: 'REF_TRANSPORT_MEANS', REF_PAYTERMS: 'REF_PAYTERMS_GROUP', REF_TRANSPORT_FCC_SUBGROUP: 'REF_TRANSPORT_FCC_GROUP',
  REF_TRANSPORT_FCC: 'REF_TRANSPORT_FCC_SUBGROUP', REF_NACAM_BRANCHES: 'REF_NACAM_SECTIONS', REF_NACAM: 'REF_NACAM_BRANCHES',
  REF_COUNTRY_SUBDIVISION: 'REF_COUNTRY_SUBDIVISION', REF_NDP: 'REF_NDP_CHAPTER',
};
const CLES_ETRANGERES = {
  REF_CURRENCY_ENTITY: { CountryIdAlpha2: 'REF_COUNTRY' }, REF_UNLOCODE: { CountryIdAlpha2: 'REF_COUNTRY' },
  REF_TRANSPORT_MEANS: { ModeTransportCode: 'REF_TRANSPORT_MODE' }, REF_MEASUREMENT_UNIT: { UnitMeasureCategory: 'REF_MEASUREMENT_UNIT_CATEGORIES' },
};

// ---------------------------------------------------------------------------------------------
function principal() {
  console.log(`Sources : ${SOURCES}`);
  const { inventaire, fiches, definitions } = analyserRapport(lireDocx(path.join(SOURCES, RAPPORT)));
  console.log(`Rapport : ${inventaire.size} tables inventoriées, ${fiches.size} fiches, ${definitions.length} structures REP_.`);

  const tables = new Map();
  const metaDeFiche = num => {
    const f = fiches.get(num), inv = inventaire.get(num);
    const m = { ...(f?.meta ?? {}) };
    delete m.shortLabel;
    return {
      number: num, ficheTitle: f ? phraseCase(f.titre) : inv ? phraseCase(inv.titre) : null,
      standards: m.standards ?? inv?.standards ?? null, producer: m.producer ?? inv?.producteur ?? null,
      updateAuthority: inv?.autorite ?? null, obtentionMode: m.obtentionMode ?? inv?.obtention ?? null,
      updateMode: inv?.actualisation ?? m.maintenanceRules ?? null, description: m.description ?? null,
      metadata: Object.fromEntries(Object.entries(m).filter(([k]) => !['standards', 'producer', 'obtentionMode', 'description'].includes(k))),
    };
  };
  const ajouterTable = (code, base) => {
    if (tables.has(code)) return tables.get(code);
    const t = { code, ...base };
    tables.set(code, t);
    return t;
  };

  // 1. Structures décrites dans le rapport
  const vus = new Set();
  for (const d of definitions) {
    if (vus.has(d.code)) { // même table décrite deux fois : on complète les colonnes
      const t = tables.get(d.code);
      for (const c of d.colonnes) if (!t.columns.some(x => x.key === (c.balise || slugCle(c.libelle)))) t.columns.push(colonneDepuisRapport(c, t.columns.length));
      continue;
    }
    vus.add(d.code);
    const fiche = d.fiche ?? '1.01';
    const meta = metaDeFiche(fiche);
    const principale = !definitions.some(x => x.fiche === d.fiche && definitions.indexOf(x) < definitions.indexOf(d));
    const libelle = d.libelle && !/^pays$/i.test(d.libelle) ? d.libelle : null;
    const colonnes = d.colonnes.length ? attribuerRoles(d.colonnes.map(colonneDepuisRapport)) : colonnesSimples('Libellé');
    ajouterTable(d.code, {
      ...meta, nameFr: principale ? meta.ficheTitle : phraseCase(libelle ?? d.code.replace(/^REF_/, '').replace(/_/g, ' ')),
      category: categorieDuNumero(fiche), sourceDocument: RAPPORT, columns: colonnes,
    });
  }
  // 2. Fiches sans structure détaillée : tables citées dans le « Libellé court »
  for (const [num, f] of fiches) {
    if (definitions.some(d => d.fiche === num)) continue;
    const reps = [...(f.meta.shortLabel ?? '').matchAll(/\[\s*(REP_[A-Z0-9_ ]+?)\s*\]/g)].map(m => canonique(m[1]));
    for (const code of (reps.length ? reps : [`REF_FICHE_${num.replace('.', '_')}`])) {
      ajouterTable(code, { ...metaDeFiche(num), nameFr: phraseCase(f.titre), category: categorieDuNumero(num), sourceDocument: RAPPORT, columns: colonnesSimples('Libellé') });
    }
  }
  // 3. Tables de l'inventaire sans fiche
  for (const [num, inv] of inventaire) {
    if (fiches.has(num)) continue;
    ajouterTable(`REF_TABLE_${num.replace('.', '_')}`, { ...metaDeFiche(num), nameFr: phraseCase(inv.titre), category: categorieDuNumero(num), sourceDocument: RAPPORT, columns: colonnesSimples('Libellé') });
  }

  // 4. Données des classeurs
  const sections = [
    ...lireSections(path.join(SOURCES, CLASSEUR_CEFACT)).map(s => ({ ...s, classeur: CLASSEUR_CEFACT })),
    ...lireSections(path.join(SOURCES, CLASSEUR_NACAM)).filter(s => s.feuille !== 'Arborescence').map(s => ({ ...s, classeur: CLASSEUR_NACAM })),
  ];
  const donnees = new Map();
  for (const s of sections) {
    let code = RENOMMAGE_SECTIONS[s.ref] ?? s.ref;
    if (code === 'REF_CURRENCY') code = 'REF_CURRENCY_ENTITY';
    const map = CORRESPONDANCES[code] ?? { code: s.entete[0], fr: s.entete[1] };
    if (!tables.has(code)) {
      const info = TABLES_CLASSEUR[code] ?? {};
      const meta = metaDeFiche(info.fiche ?? '1.01');
      ajouterTable(code, { ...meta, nameFr: info.nom ?? phraseCase(code.replace(/^REF_/, '').replace(/_/g, ' ')), description: info.desc ?? meta.description,
        category: categorieDuNumero(info.fiche ?? '1.01'), sourceDocument: s.classeur, columns: colonnesSimples('Libellé').filter(c => c.role !== 'LABEL_EN' || map.en) });
    }
    const t = tables.get(code);
    t.dataSource = `${s.classeur} — onglet « ${s.feuille} »`;
    // Colonnes manquantes pour les attributs du classeur
    for (const [entete, cle] of Object.entries(map.attrs ?? {})) {
      if (!t.columns.some(c => c.key === cle)) {
        t.columns.push({ key: cle, labelFr: entete, dataType: CLES_ETRANGERES[code]?.[cle] ? 'CODE_REF' : 'STRING', refTable: CLES_ETRANGERES[code]?.[cle] ?? null,
          required: false, role: 'ATTRIBUTE', order: t.columns.length + 1 });
      }
    }
    for (const [cle, ref] of Object.entries(CLES_ETRANGERES[code] ?? {})) {
      const c = t.columns.find(x => x.key === cle);
      if (c && c.role === 'ATTRIBUTE') { c.dataType = 'CODE_REF'; c.refTable = ref; }
    }
    const val = (r, sel) => typeof sel === 'function' ? sel(r) : sel ? (r[sel] ?? '') : '';
    const lignes = s.donnees.map(r => {
      const a = {};
      for (const [entete, cle] of Object.entries(map.attrs ?? {})) if (r[entete] !== '' && r[entete] != null) a[cle] = r[entete];
      return { c: String(val(r, map.code)).trim(), fr: String(val(r, map.fr)).trim(), en: String(val(r, map.en)).trim() || null,
        p: String(val(r, map.parent)).trim() || null, a };
    }).filter(e => e.c);
    donnees.set(code, [...(donnees.get(code) ?? []), ...lignes]);
  }

  // 5. Monnaies uniques (REF_CURRENCY) dérivées de la table des entités
  if (donnees.has('REF_CURRENCY_ENTITY')) {
    const uniques = new Map();
    for (const e of donnees.get('REF_CURRENCY_ENTITY')) {
      const cur = e.a.CurrencyCode;
      if (!cur || uniques.has(cur)) { if (cur) uniques.get(cur).a.CountryIdAlpha2 ??= e.a.CountryIdAlpha2; continue; }
      uniques.set(cur, { c: cur, fr: e.a.CurrencyName, en: e.a.CurrencyName, p: null,
        a: { CountryIdAlpha2: e.a.CountryIdAlpha2, CurrencyNumericCode: e.a.CurrencyNumericCode, CurrencyMinorUnit: e.a.CurrencyMinorUnit } });
    }
    donnees.set('REF_CURRENCY', [...uniques.values()]);
    const t = tables.get('REF_CURRENCY');
    for (const [cle, lib] of [['CurrencyNumericCode', 'Code numérique ISO 4217'], ['CurrencyMinorUnit', 'Décimales']]) {
      if (!t.columns.some(c => c.key === cle)) t.columns.push({ key: cle, labelFr: lib, dataType: 'STRING', required: false, role: 'ATTRIBUTE', order: t.columns.length + 1 });
    }
    t.dataSource = tables.get('REF_CURRENCY_ENTITY').dataSource + ' (monnaies distinctes)';
  }

  // 6. Hiérarchies, dédoublonnage, BSP, contrôle des clés étrangères
  for (const [code, parent] of Object.entries(PARENTS)) if (tables.has(code)) tables.get(code).parentTable = parent;
  for (const [code, lignes] of donnees) {
    const vus = new Map();
    // Codes répétés (ex. Rec. 23 : libellés équivalents d'un même code) : premier libellé retenu, les autres en synonymes.
    for (const e of lignes) {
      const deja = vus.get(e.c);
      if (!deja) { vus.set(e.c, e); continue; }
      if (e.fr && e.fr !== deja.fr && !(deja.a.synonyms ?? '').split(' ; ').includes(e.fr)) deja.a.synonyms = [deja.a.synonyms, e.fr].filter(Boolean).join(' ; ');
    }
    if ([...vus.values()].some(e => e.a.synonyms)) {
      const t = tables.get(code);
      if (!t.columns.some(c => c.key === 'synonyms')) t.columns.push({ key: 'synonyms', labelFr: 'Libellés équivalents', dataType: 'STRING', required: false, role: 'ATTRIBUTE', order: t.columns.length + 1 });
    }
    donnees.set(code, [...vus.values()]);
    const t = tables.get(code);
    // Libellé FR obligatoire : à défaut, le code
    for (const e of vus.values()) if (!e.fr) e.fr = e.en ?? e.c;
    const codeCol = t.columns.find(c => c.role === 'CODE');
    const maxLen = Math.max(...[...vus.values()].map(e => e.c.length));
    if (codeCol && codeCol.maxLength && maxLen > codeCol.maxLength) codeCol.maxLength = maxLen;
    for (const c of t.columns) if (c.maxLength && c.role !== 'CODE') {
      const k = c.role === 'LABEL_FR' ? 'fr' : c.role === 'LABEL_EN' ? 'en' : null;
      const m = Math.max(0, ...[...vus.values()].map(e => String((k ? e[k] : e.a[c.key]) ?? '').length));
      if (m > c.maxLength) c.maxLength = m;
    }
    for (const c of t.columns) if (c.pattern && [...vus.values()].some(e => e.a[c.key] && !new RegExp(c.pattern).test(e.a[c.key]))) c.pattern = null;
  }
  for (const [code, [fr, en]] of Object.entries(NOMS)) if (tables.has(code)) Object.assign(tables.get(code), { nameFr: fr, nameEn: en });
  const codesTables = new Set(tables.keys());
  for (const t of tables.values()) {
    t.bspPhases = phasesBsp(t.code);
    t.source = CATEGORIES.find(c => c.code === t.category)?.source ?? 'NATIONALE';
    for (const c of t.columns) if (c.dataType === 'CODE_REF' && !codesTables.has(c.refTable)) { c.comment = [c.comment, `Référence ${c.refTable} non décrite`].filter(Boolean).join(' — '); c.dataType = 'STRING'; c.refTable = null; }
    if (t.parentTable && !codesTables.has(t.parentTable)) t.parentTable = null;
    // Clés uniques (insensibles à la casse : elles deviennent des noms de colonnes SQL) et rôles uniques
    const cles = new Set(), roles = new Set();
    for (const c of t.columns) {
      c.role ??= 'ATTRIBUTE';
      delete c.primaryKey;
      let k = c.key.replace(/[^A-Za-z0-9_]/g, '').replace(/^[^A-Za-z]+/, '') || 'attribut';
      for (let n = 2; cles.has(k.toLowerCase()); n++) k = `${c.key}_${n}`;
      c.key = k; cles.add(k.toLowerCase());
      if (c.role !== 'ATTRIBUTE') { if (roles.has(c.role)) c.role = 'ATTRIBUTE'; else roles.add(c.role); }
    }
    t.columns.forEach((c, i) => { c.order = i + 1; });
  }

  // 7. Écriture
  fs.rmSync(SORTIE, { recursive: true, force: true });
  fs.mkdirSync(path.join(SORTIE, 'data'), { recursive: true });
  const listeTables = [...tables.values()].sort((a, b) => (a.number ?? '').localeCompare(b.number ?? '', 'fr', { numeric: true }) || a.code.localeCompare(b.code));
  for (const t of listeTables) t.entryCount = donnees.get(t.code)?.length ?? 0;
  fs.writeFileSync(path.join(SORTIE, 'catalogue.json'), JSON.stringify({
    generatedAt: new Date().toISOString(),
    sources: [RAPPORT, CLASSEUR_CEFACT, CLASSEUR_NACAM],
    categories: CATEGORIES, tables: listeTables,
  }, null, 1));
  let total = 0;
  for (const [code, lignes] of donnees) {
    total += lignes.length;
    fs.writeFileSync(path.join(SORTIE, 'data', `${code}.jsonl.gz`), zlib.gzipSync(lignes.map(e => JSON.stringify(e)).join('\n')));
  }
  console.log(`Catalogue : ${listeTables.length} tables, ${donnees.size} avec données, ${total} entrées.`);
  for (const t of listeTables.filter(t => t.entryCount)) console.log(`  ${t.code.padEnd(42)} ${String(t.entryCount).padStart(7)}  ${t.nameFr}`);
}

principal();
