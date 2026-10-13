import assert from 'node:assert/strict';
import {readFileSync, readdirSync, existsSync, statSync} from 'node:fs';
import {join, dirname, resolve} from 'node:path';
const root = resolve('site');
function files(dir) { return readdirSync(dir).flatMap(name => { const path = join(dir,name); return statSync(path).isDirectory() ? files(path) : [path]; }); }
assert(existsSync(root), 'Build the documentation site first');
const all = files(root);
const pages = all.filter(path => path.endsWith('.html'));
assert(pages.length >= 10, 'Guide pages missing');
for (const path of all.filter(path => /\.(html|json)$/.test(path))) {
  const content = readFileSync(path,'utf8');
  assert(!/ibm(?:-my)?\.sharepoint\.com|alexandra_freeman|w25cb891e|w17d51aa|w4b208e68/i.test(content), `Private workbook link in ${path}`);
}
for (const path of pages) {
  const html = readFileSync(path,'utf8');
  for (const match of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
    const href = match[1].split('#')[0].split('?')[0];
    if (!href || /^(?:[a-z]+:|\/\/|\/)/i.test(href)) continue;
    const target = resolve(dirname(path),decodeURIComponent(href));
    assert(target.startsWith(root), `Link leaves published site: ${path}: ${href}`);
    assert(existsSync(target), `Missing local asset or link: ${path}: ${href}`);
  }
}
assert(existsSync(join(root,'assets/screenshots/completed-report.png')));
assert(existsSync(join(root,'assets/vendor/mermaid-12.1.0.min.js')));
console.log(`Validated ${pages.length} HTML pages, local links/assets and public workbook-link boundary.`);
