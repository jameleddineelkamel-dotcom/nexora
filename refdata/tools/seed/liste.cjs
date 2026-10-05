const c = JSON.parse(require('fs').readFileSync('../../backend/src/main/resources/seed/catalogue.json', 'utf8'));
for (const t of c.tables) console.log(`${(t.number ?? '').padEnd(5)} ${t.category.padEnd(14)} ${t.code.padEnd(40)} ${String(t.entryCount).padStart(6)} ${t.bspPhases.join('/').padEnd(12)} ${t.nameFr}`);
const cnt = c.tables.reduce((m, t) => (m[t.columns.length > 3 ? 'complexe' : 'simple']++, m), { simple: 0, complexe: 0 });
console.log(JSON.stringify(cnt));
