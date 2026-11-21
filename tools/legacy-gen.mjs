import fs from 'node:fs';
import vm from 'node:vm';

const A6 = {};
const ctx = vm.createContext({
  A6, console,
  localStorage: { getItem: () => null, setItem: () => {} },
  document: { getElementById: () => null }
});
ctx.window = ctx;

for (const f of ['catalog', 'store', 'codegen']) {
  vm.runInContext(fs.readFileSync(new URL('../legacy-web/js/' + f + '.js', import.meta.url), 'utf8'), ctx, { filename: f + '.js' });
}

const p = A6.store.exampleProject();
fs.mkdirSync('build/crosscheck', { recursive: true });
fs.writeFileSync('build/crosscheck/legacy.js', A6.gen.code(p));
fs.writeFileSync('build/crosscheck/legacy-marked.js', A6.gen.code(p, { markers: true }));
console.log('legacy written');
