// Génère V6 (complétion SCRDM D23B + couche BSP des supports de formation) et V7 (valeurs des listes de codes D23B).
const fs = require('fs');
const g = require('./scrdm-modele.cjs');
const OUT = __dirname + '/../../backend/src/main/resources/db/migration/';
const SRC = (process.env.SCRDM_SOURCE || __dirname + '/source') + '/';
const v2 = require('./lire-v2.cjs')(OUT + 'V2__rdm_scrdm_d23b.sql');
const B = require('./bsp-donnees.cjs');
const CL = SRC + 'codelists/';

const q = s => s == null ? 'NULL' : "'" + String(s).replace(/'/g, "''") + "'";
const REL = "(SELECT release_id FROM rdm.standard_release WHERE family='SCRDM' AND release_code='D23B')";
const lignes = (rows, fn) => rows.map(fn).join(',\n') + ';\n';

// ---------------------------------------------------------------- racine (V2) + ABIE générés
const racine = v2.bie.filter(b => b[0] === 'scrdmMaster').map(b => ({ property: b[1], target: b[6], kind: 'ASBIE' }));
const abies = { ...g.abies, scrdmMaster: { props: racine, source: 'SCRDM' } };
const S = require(SRC + 'SCRDMMaster.json');
const exclusions = [...g.exclusions];
(function racineExcl(base, o) { // restrictions du message maître SCRDM (hors propriétés exclues du BSP Master, déjà absentes)
  const p = o?.allOf?.[1]?.items?.properties || o?.items?.properties || o?.properties;
  if (!p) return;
  for (const [k, v] of Object.entries(p)) { const c = base ? base + '/' + k : k; if (v === false) { if (base) exclusions.push({ abie: 'scrdmMaster', path: c }); } else racineExcl(c, v); }
})('', { properties: { tradeTransaction: S.allOf[1].properties.tradeTransaction } });

/** Niveau d'une restriction : BIE (propriété exclue) ou SUPPLEMENTARY (attribut d'un BBIE, ex. format, languageLocaleId). */
function niveau(abie, chemin) {
  let a = abie;
  const segs = chemin.split('/');
  for (let i = 0; i < segs.length; i++) {
    const b = abies[a]?.props.find(p => p.property === segs[i]);
    if (!b) return i === segs.length - 1 ? 'SUPPLEMENTARY' : null;
    if (b.kind === 'BBIE') return i === segs.length - 1 ? 'BIE' : 'SUPPLEMENTARY';
    a = b.target;
  }
  return 'BIE';
}
const restr = exclusions.map(e => ({ ...e, level: niveau(e.abie, e.path) }));
if (restr.some(r => !r.level)) throw new Error('restriction non résolue ' + JSON.stringify(restr.filter(r => !r.level)));

/** Résolution d'un chemin depuis la racine, restrictions contextuelles comprises. */
function resoudre(chemin) {
  const segs = chemin.split('/');
  let a = 'scrdmMaster', b;
  const vus = [];
  for (let i = 0; i < segs.length; i++) {
    vus.push([a, i]);
    for (const [x, j] of vus) { const suffixe = segs.slice(j, i + 1).join('/'); if (restr.some(r => r.abie === x && r.level === 'BIE' && r.path === suffixe)) return null; }
    b = abies[a]?.props.find(p => p.property === segs[i]);
    if (!b) return null;
    if (i < segs.length - 1) { if (b.kind !== 'ASBIE') return null; a = b.target; }
  }
  return b;
}
const verifier = (chemin, quoi) => { if (!resoudre(chemin)) throw new Error('Chemin BIE invalide (' + quoi + ') : ' + chemin); };

// ---------------------------------------------------------------- listes de codes
const listes = [];
for (const f of fs.readdirSync(CL).sort()) {
  const j = JSON.parse(fs.readFileSync(CL + f, 'utf8'));
  const [k, d] = Object.entries(j.$defs.codeList.$defs)[0];
  const meta = {};
  (d.description || '').replace(/\r/g, '').split(/\n\n/).forEach(bloc => { const m = bloc.match(/^([^:\n]+):\n([\s\S]+)$/); if (m) meta[m[1].trim()] = m[2].trim(); });
  const id = f.replace(/\.json$/, '');
  listes.push({ id, typeName: k, agency: id.split('_')[0], name: d.title, description: (d.description || '').replace(/\r/g, '').split(/\n\n/)[0].slice(0, 2000) || null,
    agencyCode: meta['Code list responsible agency code'] || null, listRef: meta['Code list'] || null, listName: meta['Code list name'] || null,
    version: meta['Code list version'] || null, issue: meta['Code list issue date'] || null, closed: !!d.oneOf,
    entries: Object.values((d.oneOf || []).reduce((m, o) => { const c = String(o.const); (m[c] = m[c] || [c, []])[1].push(o.title || null); return m; }, {})),
    uri: 'https://raw.githubusercontent.com/uncefact/spec-JSONschema/main/JSONschema2020-12/library/BuyShipPay/D23B/codelists/' + f });
}
const listeIds = new Set(listes.map(l => l.id));

// ---------------------------------------------------------------- types de données utilisés
for (const t of Object.values(g.types)) for (const c of ['codeList', 'unitList']) if (t[c] && !listeIds.has(t[c])) throw new Error('liste inconnue ' + t[c]);
const typesUtilises = new Set(Object.values(abies).flatMap(a => a.props).filter(b => b.kind === 'BBIE').map(b => b.dataType));
for (const b of v2.bie) if (b[3] === 'BBIE') typesUtilises.add(b[5]);
for (const t of typesUtilises) if (!g.types[t]) throw new Error('type inconnu ' + t);

// ---------------------------------------------------------------- écarts avec V2
const V = new Map(v2.bie.map(b => [b[0] + '/' + b[1], b]));
const nouveaux = [], cardinalites = [];
for (const [c, a] of Object.entries(g.abies)) for (const b of a.props) {
  const x = V.get(c + '/' + b.property);
  if (!x) nouveaux.push(b);
  else if (x[4] !== b.cardinality) cardinalites.push(b);
}
const vides = v2.ab.filter(a => !a[1]).map(a => a[0]);
for (const c of vides) if (!g.abies[c] || g.abies[c].source !== 'BSP') throw new Error('ABIE vide inattendu ' + c);

// ---------------------------------------------------------------- vérification des chemins de la couche BSP
for (const r of B.rolesNouveaux) verifier(r.path, r.code);
for (const [c, p] of Object.entries(B.rolesChemins)) verifier(p, c);
const v5 = fs.readFileSync(OUT + 'V5__sous_sections_regles_untded.sql', 'utf8');
for (const m of v5.matchAll(/^\('([A-Z_]+)', '[A-Z]+',[\s\S]*?\n  '([a-zA-Z/]+)',/gm)) if (!B.rolesChemins[m[1]]) verifier(m[2], 'V5 ' + m[1]);
const rolesV5 = [...v5.matchAll(/^\('([A-Z_]+)', '(TRADE|TRANSPORT|CUSTOMS|SETTLEMENT|LOCATION)'/gm)].map(m => m[1]);
if (rolesV5.length !== 17 || rolesV5.some(r => !B.groupes[r])) throw new Error('rôles V5 ' + rolesV5);
for (const e of B.entites) { verifier(e.path, e.code); for (const k of e.keyPaths || []) verifier(k, e.code); }
for (const l of B.liens) if (l.kind === 'BIE_PATH') verifier(l.target, l.instrument);
for (const l of B.liens) if (l.kind === 'CODE_LIST' && !listeIds.has(l.target)) throw new Error('liste ' + l.target);
for (const l of B.liens) if (l.kind === 'ABIE' && !abies[l.target]) throw new Error('abie ' + l.target);
const codes3035 = new Map(listes.find(l => l.id === 'UNECE_UNTDID3035').entries.map(([c, n]) => [c, n[0]]));
for (const [c, f] of Object.entries(B.fonctions3035)) if (!codes3035.has(f)) throw new Error('3035 ' + f + ' ' + c);

// ---------------------------------------------------------------- messages BSP D23B
const fichiers = require(SRC + 'list.json').map(f => f.name).filter(n => n.startsWith('UNECE-') && !/ContextCCL|BasicComponents/.test(n));
const messages = fichiers.map(n => n.replace(/^UNECE-|\.json$/g, '')).map(code => {
  const m = B.messagesDecrits[code] || {};
  const nom = code.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2').replace(/-/g, ' — ');
  return { code, standard: 'UN/CEFACT BSP-RDM', kind: /Master/.test(code) ? 'MASTER' : 'MESSAGE', name: m.name || nom, nameFr: m.fr || null, phase: m.phase || null, description: m.description || null,
    uri: 'https://github.com/uncefact/spec-JSONschema/blob/main/JSONschema2020-12/library/BuyShipPay/D23B/UNECE-' + code + '.json' };
});
for (const m of B.edifact) messages.push({ ...m, standard: 'UN/EDIFACT', kind: 'MESSAGE', uri: 'https://service.unece.org/trade/untdid/d23b/trmd/' + m.code.toLowerCase() + '_c.htm' });
const msgCodes = new Set(messages.map(m => m.code));
for (const l of B.liens) if (l.kind === 'MESSAGE' && !msgCodes.has(l.target)) throw new Error('message ' + l.target);

// ---------------------------------------------------------------- liens Recommandations ↔ listes de codes (en-tête « Code list: Rec N »)
const liens = [...B.liens];
for (const l of listes) { const m = (l.listRef || '').match(/^Rec\s*(\d+)/i); if (m) { const code = 'UNCEFACT-REC' + m[1].padStart(2, '0'); if (B.instruments.find(i => i.code === code) && !liens.find(x => x.instrument === code && x.target === l.id)) liens.push({ instrument: code, kind: 'CODE_LIST', target: l.id, note: 'Liste de codes de la Recommandation (en-tête D23B : ' + l.listRef + ')' }); } }
for (const l of liens) if (!B.instruments.find(i => i.code === l.instrument)) throw new Error('instrument ' + l.instrument);

// ================================================================= SQL V6
let s = `-- =============================================================================================
-- NEXORA · procedures — V6 : complétion du Supply Chain Reference Data Model UN/CEFACT D23B et couche Buy-Ship-Pay
-- Source normative : publication SCRDM (service.unece.org/trade/uncefact/publication/SupplyChainMGMT/SCRDM/HTML/001.htm),
--   forme machine github.com/uncefact/spec-JSONschema — library/BuyShipPay/D23B (UNECE-SCRDMContextCCL.json,
--   UNECE-SCRDMMaster.json, UNECE-BSPContextCCL.json, UNECE-BasicComponents.json, codelists/*.json).
-- Enrichissement : supports de formation e-GUCE 3G (BSP / ISCRM, Recommandations UN/CEFACT 1 à 48, MD de l'OMD,
--   EDIFACT, ebXML, NIMP, Convention FAL, ZLECAf, CMR), Douala, mai 2025.
--
--   1. ${vides.length} ABIE renvoyant à la bibliothèque BSP (vides en V2) : DEN, définition et ${nouveaux.length} BIE
--   2. ${cardinalites.length} cardinalités corrigées (maxItems = 1 → x..1 ; propriété requise → 1..x)
--   3. Types de données (${typesUtilises.size} UDT/QDT) et ${listes.length} listes de codes (valeurs : V7)
--   4. ${restr.length} restrictions contextuelles (propriétés exclues selon le chemin)
--   5. Couche BSP : domaines, processus, entités de haut niveau et leurs règles, rôles complétés,
--      messages d'échange (BSP-RDM D23B et UN/EDIFACT), instruments de référence et leurs liens
-- =============================================================================================

-- 1. ABIE : origine contextualisée (SCRDM) ou renvoi intégral à la bibliothèque BSP
ALTER TABLE rdm.abie ADD COLUMN source VARCHAR(5) NOT NULL DEFAULT 'SCRDM' CHECK (source IN ('SCRDM', 'BSP'));
COMMENT ON COLUMN rdm.abie.source IS 'SCRDM : restriction contextualisée de l''ABIE BSP ; BSP : ABIE de la bibliothèque BSP D23B repris sans restriction.';
`;
for (const c of vides) {
  const a = g.abies[c];
  s += `UPDATE rdm.abie SET den = ${q(a.den)}, definition = ${q(a.definition)}, source = 'BSP' WHERE release_id = ${REL} AND abie_code = ${q(c)};\n`;
}
s += `\nINSERT INTO rdm.bie (release_id, abie_code, property_code, den, kind, cardinality, data_type, target_abie, definition) VALUES\n`;
s += lignes(nouveaux, b => `  (${REL}, ${q(b.abie)}, ${q(b.property)}, ${q(b.den)}, '${b.kind}', '${b.cardinality}', ${q(b.dataType)}, ${q(b.target)}, ${q(b.definition)})`);

s += `\n-- 2. Cardinalités : la V2 ignorait « maxItems: 1 » (tableau JSON d'au plus un élément) et la liste « required » du CCL\n`;
s += `UPDATE rdm.bie b SET cardinality = c.card FROM (VALUES\n` + cardinalites.map(b => `  (${q(b.abie)}, ${q(b.property)}, '${b.cardinality}')`).join(',\n')
  + `\n) AS c(abie, prop, card) WHERE b.release_id = ${REL} AND b.abie_code = c.abie AND b.property_code = c.prop;\n`;

s += `
-- 3. Listes de codes et types de données
CREATE TABLE rdm.code_list (
  list_id        VARCHAR(80)  PRIMARY KEY,               -- nom du fichier D23B, ex. UNECE_UNTDID8067
  agency         VARCHAR(12)  NOT NULL,                  -- UNECE, ISO, IANA, EDIFICAS-EU
  name           VARCHAR(200) NOT NULL,
  description    TEXT,
  agency_code    VARCHAR(5),                             -- UNTDID 3055 (5 = ISO, 6 = UNECE)
  list_ref       VARCHAR(40),                            -- ex. « Rec 19 », « 4217 », « 3166-1 »
  list_name      VARCHAR(200),
  list_version   VARCHAR(20),
  issue_date     VARCHAR(20),
  closed         BOOLEAN      NOT NULL,                  -- false : liste ouverte (valeurs non énumérées)
  source_uri     TEXT
);
COMMENT ON TABLE rdm.code_list IS 'Listes de codes référencées par les types de données du BSP-RDM D23B (UNTDID, Rec. UN/CEFACT, ISO, IANA).';

CREATE TABLE rdm.code_list_entry (
  list_id        VARCHAR(80)  NOT NULL REFERENCES rdm.code_list,
  code           VARCHAR(60)  NOT NULL,
  name           TEXT,
  alt_names      TEXT[],                                 -- autres libellés du même code (ex. Rec 23 FCC, Rec 24 / UNTDID 4405)
  PRIMARY KEY (list_id, code)
);
COMMENT ON TABLE rdm.code_list_entry IS 'Valeurs des listes de codes D23B (chargées par V7).';

CREATE TABLE rdm.data_type (
  release_id        INTEGER      NOT NULL REFERENCES rdm.standard_release,
  code              VARCHAR(80)  NOT NULL,               -- ex. currencyCode (qdt), amount (udt)
  kind              VARCHAR(3)   NOT NULL CHECK (kind IN ('UDT', 'QDT')),
  den               VARCHAR(250) NOT NULL,
  definition        TEXT,
  code_list_id      VARCHAR(80)  REFERENCES rdm.code_list, -- valeurs admises du contenu
  unit_code_list_id VARCHAR(80)  REFERENCES rdm.code_list, -- unités (measure, quantity) ou devises (amount)
  agency_code       VARCHAR(5),
  PRIMARY KEY (release_id, code)
);
COMMENT ON TABLE rdm.data_type IS 'Types de données non qualifiés (UDT, CCTS) et qualifiés (QDT) des BBIE, avec leur liste de codes.';

INSERT INTO rdm.code_list (list_id, agency, name, description, agency_code, list_ref, list_name, list_version, issue_date, closed, source_uri) VALUES
`;
s += lignes(listes, l => `  (${q(l.id)}, ${q(l.agency)}, ${q(l.name)}, ${q(l.description)}, ${q(l.agencyCode)}, ${q(l.listRef)}, ${q(l.listName)}, ${q(l.version)}, ${q(l.issue)}, ${l.closed}, ${q(l.uri)})`);
const types = [...typesUtilises].sort().map(c => ({ c, ...g.types[c] }));
s += `\nINSERT INTO rdm.data_type (release_id, code, kind, den, definition, code_list_id, unit_code_list_id, agency_code) VALUES\n`;
s += lignes(types, t => `  (${REL}, ${q(t.c)}, '${t.kind}', ${q(t.den)}, ${q(t.definition)}, ${q(t.codeList)}, ${q(t.unitList)}, ${q(t.agency)})`);
s += `\nALTER TABLE rdm.bie ADD CONSTRAINT fk_bie_data_type FOREIGN KEY (release_id, data_type) REFERENCES rdm.data_type (release_id, code);\n`;

s += `
-- 4. Restrictions contextuelles : propriété (BIE) ou composant supplémentaire (SUPPLEMENTARY) exclu sous un ABIE donné,
--    le chemin étant relatif à cet ABIE (ex. logisticsPackage : includedTradeLineItem/barcodeId).
CREATE TABLE rdm.context_restriction (
  release_id     INTEGER      NOT NULL,
  abie_code      VARCHAR(80)  NOT NULL,
  path           VARCHAR(400) NOT NULL,
  level          VARCHAR(13)  NOT NULL CHECK (level IN ('BIE', 'SUPPLEMENTARY')),
  PRIMARY KEY (release_id, abie_code, path),
  FOREIGN KEY (release_id, abie_code) REFERENCES rdm.abie
);
COMMENT ON TABLE rdm.context_restriction IS 'Restrictions du SCRDM sur les ABIE réutilisés : un chemin exclu ne peut pas porter de donnée dans ce contexte.';
INSERT INTO rdm.context_restriction (release_id, abie_code, path, level) VALUES
`;
s += lignes(restr, r => `  (${REL}, ${q(r.abie)}, ${q(r.path)}, '${r.level}')`);

// ---------------------------------------------------------------- 5. couche BSP
s += `
-- =============================================================================================
-- 5. Couche Buy-Ship-Pay (ISCRM / BSP-RDM) issue des supports de formation
-- =============================================================================================
INSERT INTO rdm.standard_release (family, release_code, title, source_uri) VALUES
  ('BSP', 'D23B', 'Buy-Ship-Pay Reference Data Model — bibliothèque et structures d''échange', 'https://github.com/uncefact/spec-JSONschema/tree/main/JSONschema2020-12/library/BuyShipPay/D23B');

-- 5.1 Domaines d'activité et processus métier (Acheter, Expédier, Payer)
CREATE TABLE rdm.bsp_domain (
  code        VARCHAR(12)  PRIMARY KEY,
  label_fr    VARCHAR(80)  NOT NULL,
  label_en    VARCHAR(80)  NOT NULL,
  description TEXT         NOT NULL,
  sort_order  INT          NOT NULL
);
COMMENT ON TABLE rdm.bsp_domain IS 'Les quatre domaines d''activité de la chaîne logistique internationale (ISCRM).';
INSERT INTO rdm.bsp_domain (code, label_fr, label_en, description, sort_order) VALUES
`;
s += lignes(B.domaines, (d, i) => `  (${q(d.code)}, ${q(d.fr)}, ${q(d.en)}, ${q(d.description)}, ${(i + 1) * 10})`);
s += `
CREATE TABLE rdm.bsp_process (
  code        VARCHAR(30)  PRIMARY KEY,
  phase       VARCHAR(4)   NOT NULL CHECK (phase IN ('BUY', 'SHIP', 'PAY')),
  domain      VARCHAR(12)  NOT NULL REFERENCES rdm.bsp_domain,
  label_fr    VARCHAR(120) NOT NULL,
  label_en    VARCHAR(120) NOT NULL,
  description TEXT         NOT NULL,
  documents   TEXT[]       NOT NULL DEFAULT '{}',      -- documents typiques échangés
  sort_order  INT          NOT NULL
);
COMMENT ON TABLE rdm.bsp_process IS 'Processus métier du modèle Buy-Ship-Pay (UN/CEFACT ISCRM), de la commande au règlement.';
INSERT INTO rdm.bsp_process (code, phase, domain, label_fr, label_en, description, documents, sort_order) VALUES
`;
const arr = a => "'{" + a.map(x => '"' + x.replace(/"/g, '\\"').replace(/'/g, "''") + '"').join(',') + "}'";
s += lignes(B.processus, (p, i) => `  (${q(p.code)}, '${p.phase}', '${p.domain}', ${q(p.fr)}, ${q(p.en)}, ${q(p.description)}, ${arr(p.documents)}, ${(i + 1) * 10})`);

s += `
-- 5.2 Entités de haut niveau du BSP-RDM et leur ancrage dans le SCRDM
CREATE TABLE rdm.bsp_entity (
  code        VARCHAR(30)  PRIMARY KEY,
  label_fr    VARCHAR(120) NOT NULL,
  label_en    VARCHAR(120) NOT NULL,
  definition  TEXT         NOT NULL,
  abie_code   VARCHAR(80)  NOT NULL,                  -- ABIE SCRDM qui porte l'entité
  bie_path    VARCHAR(300) NOT NULL,                  -- chemin principal depuis scrdmMaster
  key_paths   TEXT[]       NOT NULL DEFAULT '{}',     -- chemins des données clés (identifiant, code tarifaire…)
  note        TEXT,
  sort_order  INT          NOT NULL
);
COMMENT ON TABLE rdm.bsp_entity IS 'Entités d''information de haut niveau du BSP-RDM (commande, article, expédition, envoi, déclaration…).';
INSERT INTO rdm.bsp_entity (code, label_fr, label_en, definition, abie_code, bie_path, key_paths, note, sort_order) VALUES
`;
s += lignes(B.entites, (e, i) => `  (${q(e.code)}, ${q(e.fr)}, ${q(e.en)}, ${q(e.definition)}, ${q(resoudre(e.path).target || e.path)}, ${q(e.path)}, ${arr(e.keyPaths || [])}, ${q(e.note)}, ${(i + 1) * 10})`);
for (const e of B.entites) if (!resoudre(e.path).target) throw new Error('entité sans ABIE ' + e.code);
s += `
CREATE TABLE rdm.bsp_entity_relation (
  from_entity VARCHAR(30)  NOT NULL REFERENCES rdm.bsp_entity,
  to_entity   VARCHAR(30)  NOT NULL REFERENCES rdm.bsp_entity,
  cardinality VARCHAR(6)   NOT NULL CHECK (cardinality IN ('0..1','1..1','0..n','1..n')),
  rule_fr     TEXT         NOT NULL,
  PRIMARY KEY (from_entity, to_entity)
);
COMMENT ON TABLE rdm.bsp_entity_relation IS 'Règles de gestion du modèle de haut niveau BSP-RDM (cardinalités entre entités).';
INSERT INTO rdm.bsp_entity_relation (from_entity, to_entity, cardinality, rule_fr) VALUES
`;
s += lignes(B.relations, r => `  (${q(r.from)}, ${q(r.to)}, '${r.card}', ${q(r.rule)})`);
s += `
CREATE TABLE rdm.bsp_entity_party (
  entity      VARCHAR(30)  NOT NULL REFERENCES rdm.bsp_entity,
  party_role  VARCHAR(40)  NOT NULL REFERENCES rdm.party_role,
  cardinality VARCHAR(6)   NOT NULL CHECK (cardinality IN ('0..1','1..1','0..n','1..n')),
  rule_fr     TEXT         NOT NULL,
  PRIMARY KEY (entity, party_role)
);
COMMENT ON TABLE rdm.bsp_entity_party IS 'Parties attachées à une entité BSP (ex. un envoi n''a qu''un expéditeur).';
`;

// rôles
s += `
-- 5.3 Rôles des parties : groupes d'acteurs (client, fournisseur, intermédiaire, autorité), code fonction UNTDID 3035, nouveaux rôles
ALTER TABLE rdm.party_role DROP CONSTRAINT party_role_category_check;
ALTER TABLE rdm.party_role ADD CONSTRAINT party_role_category_check
  CHECK (category IN ('TRADE', 'TRANSPORT', 'CUSTOMS', 'SETTLEMENT', 'LOCATION', 'FINANCIAL', 'INSURANCE', 'AUTHORITY'));
ALTER TABLE rdm.party_role ADD COLUMN actor_group VARCHAR(12) CHECK (actor_group IN ('CUSTOMER', 'SUPPLIER', 'INTERMEDIARY', 'AUTHORITY'));
ALTER TABLE rdm.party_role ADD COLUMN function_code VARCHAR(3);   -- UNTDID 3035 Party function code qualifier
COMMENT ON COLUMN rdm.party_role.actor_group IS 'Catégorie d''acteur du BSP : client, fournisseur, intermédiaire ou autorité.';
COMMENT ON COLUMN rdm.party_role.function_code IS 'Code fonction de la partie (UNTDID 3035), valeur de Trade_ Party. Role. Code.';
`;
for (const [c, p] of Object.entries(B.rolesChemins)) s += `UPDATE rdm.party_role SET bie_path = ${q(p)} WHERE code = ${q(c)};\n`;
for (const [c, grp] of Object.entries(B.groupes)) s += `UPDATE rdm.party_role SET actor_group = ${q(grp)}, function_code = ${q(B.fonctions3035[c] || null)} WHERE code = ${q(c)};\n`;
s += `INSERT INTO rdm.party_role (code, category, label_en, label_fr, definition, bie_path, mapped_to, keywords, sort_order, actor_group, function_code) VALUES\n`;
s += lignes(B.rolesNouveaux, (r, i) => `  (${q(r.code)}, '${r.category}', ${q(r.en)}, ${q(r.fr)}, ${q(r.definition)}, ${q(r.path)}, ${q(r.mapped)}, ${arr(r.keywords)}, ${200 + i * 10}, ${q(r.group)}, ${q(B.fonctions3035[r.code] || null)})`);
s += `\nINSERT INTO rdm.bsp_entity_party (entity, party_role, cardinality, rule_fr) VALUES\n`;
s += lignes(B.entitesParties, r => `  (${q(r.entity)}, ${q(r.role)}, '${r.card}', ${q(r.rule)})`);

// messages
s += `
-- 5.4 Messages d'échange : structures BSP-RDM D23B (dont le message maître SCRDM) et messages UN/EDIFACT cités
CREATE TABLE rdm.exchange_message (
  code        VARCHAR(80)  PRIMARY KEY,
  standard    VARCHAR(20)  NOT NULL CHECK (standard IN ('UN/CEFACT BSP-RDM', 'UN/EDIFACT')),
  kind        VARCHAR(7)   NOT NULL CHECK (kind IN ('MASTER', 'MESSAGE')),
  name        VARCHAR(200) NOT NULL,
  name_fr     VARCHAR(200),
  phase       VARCHAR(4)   CHECK (phase IN ('BUY', 'SHIP', 'PAY')),
  description TEXT,
  source_uri  TEXT
);
COMMENT ON TABLE rdm.exchange_message IS 'Structures d''échange de données (BSP-RDM D23B, CCBDA) et messages UN/EDIFACT de référence.';
INSERT INTO rdm.exchange_message (code, standard, kind, name, name_fr, phase, description, source_uri) VALUES
`;
s += lignes(messages, m => `  (${q(m.code)}, ${q(m.standard)}, '${m.kind}', ${q(m.name)}, ${q(m.nameFr)}, ${q(m.phase)}, ${q(m.description)}, ${q(m.uri)})`);

// instruments
s += `
-- 5.5 Instruments de référence (Recommandations UN/CEFACT, normes, conventions) et leurs liens avec le modèle
CREATE TABLE rdm.reference_instrument (
  code        VARCHAR(30)  PRIMARY KEY,                -- ex. UNCEFACT-REC33, IPPC-ISPM12, IMO-FAL2
  family      VARCHAR(16)  NOT NULL CHECK (family IN ('UNCEFACT_REC', 'UNCEFACT_STD', 'ISO', 'WCO', 'IMO_FAL', 'IPPC_ISPM', 'CONVENTION', 'AGREEMENT')),
  number      VARCHAR(10),
  title_fr    VARCHAR(300) NOT NULL,
  doc_ref     VARCHAR(80),                             -- cote du document (ex. ECE/TRADE/352/Rev.1)
  issued      VARCHAR(20),
  summary_fr  TEXT,
  url         TEXT,
  sort_order  INT          NOT NULL
);
COMMENT ON TABLE rdm.reference_instrument IS 'Recommandations UN/CEFACT, normes (EDIFACT, ebXML, CCTS, UNTDED), MD de l''OMD, FAL, NIMP, CMR, ZLECAf.';
INSERT INTO rdm.reference_instrument (code, family, number, title_fr, doc_ref, issued, summary_fr, url, sort_order) VALUES
`;
s += lignes(B.instruments, (i, k) => `  (${q(i.code)}, '${i.family}', ${q(i.number)}, ${q(i.title)}, ${q(i.doc)}, ${q(i.issued)}, ${q(i.summary)}, ${q(i.url)}, ${(k + 1) * 10})`);
s += `
CREATE TABLE rdm.instrument_link (
  instrument  VARCHAR(30)  NOT NULL REFERENCES rdm.reference_instrument,
  target_kind VARCHAR(12)  NOT NULL CHECK (target_kind IN ('CODE_LIST', 'MESSAGE', 'ABIE', 'BIE_PATH', 'PARTY_ROLE', 'BSP_ENTITY', 'BSP_PROCESS')),
  target      VARCHAR(300) NOT NULL,
  note        TEXT,
  PRIMARY KEY (instrument, target_kind, target)
);
COMMENT ON TABLE rdm.instrument_link IS 'Ce qu''un instrument normalise dans le modèle : liste de codes, message, ABIE, chemin BIE, rôle, entité ou processus.';
INSERT INTO rdm.instrument_link (instrument, target_kind, target, note) VALUES
`;
s += lignes(liens, l => `  (${q(l.instrument)}, '${l.kind}', ${q(l.target)}, ${q(l.note)})`);
for (const l of liens) {
  if (l.kind === 'PARTY_ROLE' && !B.rolesNouveaux.find(r => r.code === l.target) && !B.groupes[l.target]) throw new Error('rôle ' + l.target);
  if (l.kind === 'BSP_ENTITY' && !B.entites.find(e => e.code === l.target)) throw new Error('entité ' + l.target);
  if (l.kind === 'BSP_PROCESS' && !B.processus.find(e => e.code === l.target)) throw new Error('processus ' + l.target);
}
s += `\nCREATE INDEX ix_instrument_link_target ON rdm.instrument_link (target_kind, target);\n`;

fs.writeFileSync(OUT + 'V6__scrdm_complet_bsp.sql', s);

// ================================================================= SQL V7 : valeurs des listes de codes
let v7 = `-- =============================================================================================
-- NEXORA · procedures — V7 : valeurs des listes de codes du BSP-RDM D23B (${listes.reduce((n, l) => n + l.entries.length, 0)} codes, ${listes.filter(l => l.closed).length} listes fermées)
-- Source : github.com/uncefact/spec-JSONschema — library/BuyShipPay/D23B/codelists
-- =============================================================================================
`;
for (const l of listes.filter(l => l.entries.length)) {
  v7 += `INSERT INTO rdm.code_list_entry (list_id, code, name) VALUES\n` + lignes(l.entries, ([c, n]) => `  (${q(l.id)}, ${q(c)}, ${q(n)})`);
}
fs.writeFileSync(OUT + 'V7__rdm_listes_de_codes_d23b.sql', v7);

console.log({ vides: vides.length, nouveaux: nouveaux.length, cardinalites: cardinalites.length, types: types.length, listes: listes.length,
  codes: listes.reduce((n, l) => n + l.entries.length, 0), restrictions: restr.length, messages: messages.length, instruments: B.instruments.length, liens: liens.length,
  roles: B.rolesNouveaux.length, entites: B.entites.length, relations: B.relations.length });
module.exports = { resoudre };
