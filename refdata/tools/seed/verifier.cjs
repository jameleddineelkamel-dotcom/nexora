const c = JSON.parse(require('fs').readFileSync('../../backend/src/main/resources/seed/catalogue.json', 'utf8'));
let pb = 0;
for (const t of c.tables) for (const col of t.columns) if (!col.role || !col.dataType || !col.labelFr || !col.key) { pb++; console.log(t.code, JSON.stringify(col).slice(0, 150)); }
for (const t of c.tables) if (t.columns.filter(x => x.role === 'CODE').length !== 1) { pb++; console.log('CODE ?', t.code); }
console.log('problèmes :', pb);
