// Reconstruit le SCRDM D23B complet (ABIE, BIE, restrictions contextuelles, types de données) depuis spec-JSONschema.
const fs = require('fs');
const G = (process.env.SCRDM_SOURCE || __dirname + '/source') + '/';
const S = require(G + 'SCRDMContextCCL.json').$defs;
const B = require(G + 'BSPContextCCL.json').$defs;
const BC = require(G + 'BasicComponents.json').$defs;

const nom = ref => ref.split('/').pop().replace(/Type$/, '');
const fichier = ref => ref.startsWith('#') ? null : ref.split('#')[0];

/** Description d'une propriété : { items?, $ref?, maxItems, properties (restrictions) } → BIE */
function bie(abie, prop, v, required, ctxFile) {
  const arr = v.type === 'array' || v.items;
  const core = v.items || v;
  let ref = core.$ref;
  if (!ref && core.oneOf) ref = core.oneOf.map(o => o.$ref).find(r => r && !r.includes('resourceType'));
  if (!ref && core.allOf) {
    const f = core.allOf.find(o => o.$ref || o.items || o.oneOf);
    return bie(abie, prop, { ...v, ...f, title: v.title, description: v.description }, required, ctxFile);
  }
  const isUdt = ref && ref.includes('/udt/'), isQdt = ref && ref.includes('/qdt/');
  const kind = isUdt || isQdt ? 'BBIE' : 'ASBIE';
  const min = required ? '1' : '0';
  const max = arr && v.maxItems !== 1 ? 'n' : '1';
  return { abie, property: prop, den: v.title || '', kind, cardinality: min + '..' + max, dataType: kind === 'BBIE' ? nom(ref) : null,
    target: kind === 'ASBIE' ? nom(ref) : null, definition: v.description || '', ref, restr: core.properties || (v.items ? null : v.properties) };
}

function proprietesBase(def, ctx) {
  const req = new Set(def.required || []);
  return Object.entries(def.properties || {}).map(([k, v]) => bie(null, k, v, req.has(k), ctx));
}

const abies = {}; // code -> {den, definition, props:[], source}
const exclusions = []; // {abie, path}
function collecteExclusions(abie, base, obj) {
  if (!obj || typeof obj !== 'object') return;
  const p = obj.items?.properties || obj.properties || obj.allOf?.find(x => x.items?.properties || x.properties)?.items?.properties
    || obj.allOf?.find(x => x.properties)?.properties;
  if (!p) return;
  for (const [k, v] of Object.entries(p)) {
    const chemin = base ? base + '/' + k : k;
    if (v === false) exclusions.push({ abie, path: chemin });
    else collecteExclusions(abie, chemin, v);
  }
}

// 1. ABIE du SCRDM : restriction (allOf) ou renvoi pur vers la bibliothèque BSP
for (const [k, def] of Object.entries(S)) {
  if (k === 'qdt') continue;
  const code = nom(k);
  if (def.allOf) {
    const base = B[nom(def.allOf[0].$ref) + 'Type'];
    const ov = def.allOf[1]?.properties || {};
    const props = [];
    for (const b of proprietesBase(base)) {
      const o = ov[b.property];
      if (o === false) continue;
      if (o) {
        const ref = o.$ref || o.items?.$ref || o.items?.oneOf?.find(x => !x.$ref?.includes('resourceType'))?.$ref
          || o.allOf?.[0]?.items?.oneOf?.find(x => !x.$ref?.includes('resourceType'))?.$ref || o.allOf?.[0]?.$ref
          || o.oneOf?.find(x => !x.$ref?.includes('resourceType'))?.$ref;
        if (ref) { if (b.kind === 'BBIE') b.dataType = nom(ref); else b.target = nom(ref); }
        if (o.maxItems === 1 && b.cardinality.endsWith('n')) b.cardinality = b.cardinality[0] + '..1';
        if (o.minItems >= 1 || (def.allOf[1].required || []).includes(b.property)) b.cardinality = '1..' + b.cardinality.slice(-1);
        collecteExclusions(code, b.property, o);
      }
      props.push(b);
    }
    abies[code] = { den: base.title, definition: base.description, props, source: 'SCRDM' };
  } else if (def.$ref) {
    const base = B[nom(def.$ref) + 'Type'];
    abies[code] = { den: base.title, definition: base.description, props: proprietesBase(base), source: 'BSP' };
  }
}
// 2. Fermeture transitive : ABIE cibles absents du SCRDM, repris de la bibliothèque BSP
let ajout = true;
while (ajout) {
  ajout = false;
  for (const a of Object.values(abies)) for (const b of a.props) if (b.kind === 'ASBIE' && !abies[b.target]) {
    const base = B[b.target + 'Type'];
    if (!base) { console.error('cible inconnue', b.target); continue; }
    abies[b.target] = { den: base.title, definition: base.description, props: proprietesBase(base), source: 'BSP-closure' };
    ajout = true;
  }
}
for (const [c, a] of Object.entries(abies)) a.props.forEach(b => b.abie = c);

// 3. Types de données (udt + qdt) utilisés, avec liste de codes
const types = {};
const clDe = r => r ? r.split('/').pop().replace(/\.json.*/, '').replace(/#.*/, '') : null;
function listeDe(def, champ) {
  const p = def?.properties?.[champ];
  if (!p) return null;
  const r = p.$ref || p.allOf?.find(x => x.$ref)?.$ref;
  return r && r.includes('codelists/') ? r.split('codelists/')[1].split('.json')[0] : null;
}
function agence(def, champ) {
  const p = def?.properties?.[champ];
  return p?.allOf?.find(x => x.enum)?.enum?.[0] ?? null;
}
for (const [k, d] of Object.entries(BC.udt.$defs)) types[nom(k)] = { kind: 'UDT', den: d.title, definition: d.description, base: null,
  codeList: listeDe(d, 'content'), unitList: listeDe(d, 'unitCode') || listeDe(d, 'currencyId'), agency: null };
for (const [k, d] of Object.entries(B.qdt.$defs)) types[nom(k)] = { kind: 'QDT', den: d.title, definition: d.description,
  base: d.$ref ? nom(d.$ref) : null,
  codeList: listeDe(d, 'content'), unitList: listeDe(d, 'unitCode') || listeDe(d, 'currencyId'),
  agency: agence(d, 'listAgencyId') || agence(d, 'schemeAgencyId') };

// 4. Profondeur depuis la racine (parcours en largeur)
const prof = { scrdmMaster: 0 }, file = ['scrdmMaster'];
while (file.length) { const c = file.shift(); for (const b of abies[c]?.props || []) if (b.kind === 'ASBIE' && prof[b.target] === undefined) { prof[b.target] = prof[c] + 1; file.push(b.target); } }

module.exports = { abies, exclusions, types, prof };
if (require.main === module) {
  const all = Object.values(abies).flatMap(a => a.props);
  console.log('ABIE', Object.keys(abies).length, 'par source', Object.values(abies).reduce((m, a) => (m[a.source] = (m[a.source] || 0) + 1, m), {}));
  console.log('BIE', all.length, 'BBIE', all.filter(b => b.kind === 'BBIE').length, 'ASBIE', all.filter(b => b.kind === 'ASBIE').length);
  console.log('exclusions contextuelles', exclusions.length, 'types', Object.keys(types).length);
  console.log('non atteignables', Object.keys(abies).filter(c => prof[c] === undefined).join(' '));
}
