/* P0 纯函数单测：候选合成 / 评分 / 表达式生成
 * 运行：node tests/picker-unit.mjs
 */
import fs from 'node:fs';
import vm from 'node:vm';

const A6 = {};
const ctx = vm.createContext({
  A6,
  console,
  localStorage: { getItem: () => null, setItem: () => { } },
  window: {},
  document: { getElementById: () => null }
});
ctx.window = ctx;

for (const f of ['catalog', 'store', 'picker', 'codegen']) {
  vm.runInContext(fs.readFileSync(new URL('../js/' + f + '.js', import.meta.url), 'utf8'), ctx, { filename: f + '.js' });
}

const P = A6.picker.pure;
let fail = 0;
const eq = (name, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) { fail++; console.log('FAIL', name, '\n  got :', JSON.stringify(got), '\n  want:', JSON.stringify(want)); }
  else console.log('PASS', name);
};

const node = P.normalize({
  depth: 2, className: 'android.widget.Button', text: '天天领豆',
  id: 'com.jingdong.app.mall:id/coupon-sign', clickable: true,
  bounds: { left: 600, top: 300, right: 1000, bottom: 420 }
});
const bare = P.normalize({ depth: 2, className: 'android.widget.Button', text: '立即领取', clickable: true, bounds: { left: 0, top: 0, right: 10, bottom: 10 } });

eq('normalize center', node.center, [800, 360]);
eq('shortId', P.shortId(node.id), 'coupon-sign');
eq('shortClass', P.shortClass(node.className), 'Button');

const c1 = P.buildCandidates(node);
eq('candidate by order', c1.map(c => c.by), ['id', 'idMatches', 'text', 'textContains']);
eq('idMatches regex', c1[1].value, '.*coupon-sign$');

const c2 = P.buildCandidates(bare);
eq('no id → no id candidates', c2.map(c => c.by), ['text', 'textContains']);

eq('score id', P.scoreOf(c1[0], node, 1), 100);
eq('score text', P.scoreOf(c1[2], node, 1), 90);
eq('score textContains unique', P.scoreOf(c1[3], node, 1), 84);
eq('score textContains 2 hits', P.scoreOf(c1[3], node, 2), 76);
eq('score textContains 0 hit', P.scoreOf(c1[3], node, 0), 59);
eq('expr text', P.toExpr(c1[2]), 'text("天天领豆")');
eq('expr id', P.toExpr(c1[0]), 'id("com.jingdong.app.mall:id/coupon-sign")');

/* codegen：expr 优先，并被括号包裹 */
const cbn = A6.store.mkBlock('clickNode', { expr: 'desc("更多任务")' });
eq('expr 优先', A6.gen.block(cbn).indexOf('(desc("更多任务")).findOne(3000)') >= 0, true);
const cbd = A6.store.mkBlock('clickNode', {});
eq('无 expr 走 by/value', A6.gen.block(cbd).indexOf('text("立即领奖").findOne(3000)') >= 0, true);
const cbe = A6.store.mkBlock('existsVar', { expr: 'id("a:b")' });
eq('existsVar 用表达式', A6.gen.block(cbe).indexOf('(id("a:b")).exists()') >= 0, true);

console.log(fail ? '\n' + fail + ' 项失败' : '\n全部通过');
process.exit(fail ? 1 : 0);