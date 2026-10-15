// Télécharge la forme machine du SCRDM D23B (github.com/uncefact/spec-JSONschema, library/BuyShipPay/D23B) dans ./source.
// Usage : node telecharger-source.cjs   (Node 18 ou plus récent ; aucune dépendance)
const fs = require('fs');
const path = require('path');

const API = 'https://api.github.com/repos/uncefact/spec-JSONschema/contents/JSONschema2020-12/library/BuyShipPay/D23B';
const RAW = 'https://raw.githubusercontent.com/uncefact/spec-JSONschema/main/JSONschema2020-12/library/BuyShipPay/D23B/';
const DEST = process.env.SCRDM_SOURCE || path.join(__dirname, 'source');
const SCHEMAS = ['SCRDMMaster', 'SCRDMContextCCL', 'BSPContextCCL', 'BSPMaster', 'BasicComponents'];

async function json(url) {
  const r = await fetch(url, { headers: { 'User-Agent': 'nexora-scrdm' } });
  if (!r.ok) throw new Error(url + ' : HTTP ' + r.status);
  return r.json();
}

(async () => {
  fs.mkdirSync(path.join(DEST, 'codelists'), { recursive: true });
  const liste = await json(API);
  fs.writeFileSync(path.join(DEST, 'list.json'), JSON.stringify(liste, null, 1));
  for (const s of SCHEMAS) fs.writeFileSync(path.join(DEST, s + '.json'), JSON.stringify(await json(RAW + 'UNECE-' + s + '.json')));
  const listes = await json(API + '/codelists');
  for (const f of listes) fs.writeFileSync(path.join(DEST, 'codelists', f.name), JSON.stringify(await json(f.download_url)));
  console.log(`${SCHEMAS.length} schémas et ${listes.length} listes de codes dans ${DEST}`);
})().catch(e => { console.error(e.message); process.exit(1); });
