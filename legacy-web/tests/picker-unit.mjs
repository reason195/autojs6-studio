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

// 形参列表只保留普通标识符：自由文本不能拼出顶层可执行代码
const fd = A6.store.mkBlock('funcDef', { name: 'f', args: 'a) {} evil(); function z(b' });
eq('funcDef 形参过滤注入', A6.gen.block(fd).split('\n')[0], 'function f() {');
const fd2 = A6.store.mkBlock('funcDef', { name: 'f', args: ' a , b-c, $d, if, 9x ' });
eq('funcDef 形参过滤非法项', A6.gen.block(fd2).split('\n')[0], 'function f(a, $d) {');
const modCode = A6.gen.code({ name: 't', modules: [{ id: 'm1', name: 'm', args: 'x) {} evil(); (', children: { body: [] } }], root: { children: { body: [] } } });
eq('模块形参过滤注入', modCode.indexOf('evil') < 0 && modCode.indexOf('function mod_m() {') >= 0, true);

// 直接拼进代码的名字必须是标识符；注释里的文本不能逃出注释
const fn = A6.gen.block(A6.store.mkBlock('funcDef', { name: 'f() {} evil(); function g', args: '' }));
eq('funcDef 非法函数名跳过', fn.indexOf('function f()') < 0 && fn.indexOf('// 非法标识符已跳过') === 0 && fn.indexOf('\n') < 0, true);
const vs = A6.gen.block(A6.store.mkBlock('varSet', { name: 'x = 1; evil(); var y', expr: '2' }));
eq('varSet 非法变量名跳过', vs.indexOf('// 非法标识符已跳过') === 0, true);
eq('varSet 合法变量名照常', A6.gen.block(A6.store.mkBlock('varSet', { name: 'x', expr: '2' })).indexOf('x') >= 0, true);
const cm = A6.gen.block(A6.store.mkBlock('comment', { text: '说明\r下一行 evil()\u2028x' }));
eq('注释吞掉所有行终止符', /[\r\n\u2028\u2029]/.test(cm), false);
const hc = A6.gen.code({ name: 'p */ evil(); /*', modules: [{ id: 'm1', name: 'm', desc: 'd */ evil2(); /*', args: '', children: { body: [] } }], root: { children: { body: [] } } });
eq('工程名/模块说明不能闭合块注释', hc.indexOf('*/ evil') < 0 && hc.indexOf('* / evil();') >= 0 && hc.indexOf('* / evil2();') >= 0, true);

// AutoJs6 手势与按键正确签名 + launchPackage(wait=0) + 纯空格函数名兜底
eq('edgeBackGesture 正确手势签名', A6.gen.block(A6.store.mkBlock('edgeBackGesture', {})), 'gesture(320, [2, device.height * 0.5], [device.width * 0.65, device.height * 0.5]);');
eq('jdCrossApp 正确手势签名', A6.gen.block(A6.store.mkBlock('jdCrossApp', {})).indexOf('gesture(320, [2, device.height * 0.5], [device.width * 0.65, device.height * 0.5]);') >= 0, true);
eq('pressKey back', A6.gen.block(A6.store.mkBlock('pressKey', { key: 'back' })), 'back();');
eq('pressKey home', A6.gen.block(A6.store.mkBlock('pressKey', { key: 'home' })), 'home();');
eq('pressKey recents', A6.gen.block(A6.store.mkBlock('pressKey', { key: 'recents' })), 'recents();');
eq('pressKey menu', A6.gen.block(A6.store.mkBlock('pressKey', { key: 'menu' })), 'KeyCode("KEYCODE_MENU");');
eq('launchPackage wait=0 不生成 sleep', A6.gen.block(A6.store.mkBlock('launchPackage', { pkg: 'com.jd.jrapp', wait: 0 })), 'app.launchPackage("com.jd.jrapp");');
eq('launchPackage 默认等待', A6.gen.block(A6.store.mkBlock('launchPackage', { pkg: 'com.jd.jrapp' })), 'app.launchPackage("com.jd.jrapp");\nsleep(1500);');
eq('funcDef 纯空格函数名回退 fn', A6.gen.block(A6.store.mkBlock('funcDef', { name: '   ', args: '' })).split('\n')[0], 'function fn() {');

console.log(fail ? '\n' + fail + ' 项失败' : '\n全部通过');
process.exit(fail ? 1 : 0);