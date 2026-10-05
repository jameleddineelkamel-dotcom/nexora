const c = JSON.parse(require('fs').readFileSync('../../backend/src/main/resources/seed/catalogue.json', 'utf8'));
const t = c.tables.find(x => x.code === 'REF_COUNTRY');
console.log(JSON.stringify({ ...t, columns: t.columns.map(x => `${x.role}:${x.key}:${x.dataType}${x.refTable ? '→' + x.refTable : ''}${x.required ? '*' : ''}`) }, null, 1).slice(0, 2500));
