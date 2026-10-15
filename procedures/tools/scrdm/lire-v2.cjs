// Lecture des INSERT de V2 (ABIE et BIE) pour comparer le modèle généré à l'existant.
module.exports = fichier => {
const s=require("fs").readFileSync(fichier,"utf8");
const re=/^\s*\(\(SELECT[^)]*\), (.*)\)[,;]\s*$/gm; let m; const ab=[],bie=[];
function split(v){const parts=[];let cur="",q=false,raw=true;for(let i=0;i<v.length;i++){const c=v[i];if(q){if(c=="'"){if(v[i+1]=="'"){cur+="'";i++}else q=false}else cur+=c}else if(c=="'")q=true;else if(c==","){parts.push(cur.trim());cur=""}else cur+=c}parts.push(cur.trim());return parts}
while((m=re.exec(s))){const p=split(m[1]);(p.length==5?ab:bie).push(p)}
return {ab,bie};
};
