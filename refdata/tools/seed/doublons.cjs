const XLSX = require('xlsx');
const wb = XLSX.readFile(process.argv[2]);
for (const [name, col] of [['R28 - Moyens de transport', 1], ['R23 - Frais de transport (FCC)', 0]]) {
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[name], { header: 1, defval: '', raw: false }).slice(2).filter(r => r[col] !== '');
  const counts = new Map(); rows.forEach(r => counts.set(r[col], (counts.get(r[col]) ?? 0) + 1));
  const dups = [...counts].filter(([, n]) => n > 1);
  console.log(`${name}: ${rows.length} lignes, ${counts.size} codes distincts, ${dups.length} codes en double`);
  for (const [c] of dups.slice(0, 4)) console.log('   ' + rows.filter(r => r[col] === c).map(r => r.slice(0, 4).join(' | ').slice(0, 110)).join('\n   '));
}
