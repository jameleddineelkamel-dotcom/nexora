const XLSX = require('xlsx');
const wb = XLSX.readFile(process.argv[2]);
for (const name of process.argv.slice(3)) {
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[name], { header: 1, defval: '', blankrows: true });
  console.log(`\n=== [${name}]`);
  rows.forEach((r, i) => console.log(`${i}: ` + r.map(v => String(v).replace(/\s+/g, ' ').slice(0, 50)).join(' | ')));
}
