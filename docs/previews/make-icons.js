// Builds icons.js (Material icon paths) from res/drawable/ic_*.xml for screens.html.
const fs = require('fs'), path = require('path');
const dir = path.join(__dirname, '../../res/drawable'), icons = {};
for (const f of fs.readdirSync(dir)) {
  if (!f.startsWith('ic_') || f.includes('launcher')) continue;
  const xml = fs.readFileSync(path.join(dir, f), 'utf8');
  icons[f.slice(3, -4)] = [...xml.matchAll(/pathData="([^"]+)"/g)].map(m => m[1]);
}
fs.writeFileSync(path.join(__dirname, 'icons.js'), 'const ICONS=' + JSON.stringify(icons) + ';');
