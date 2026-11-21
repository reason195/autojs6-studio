/* AutoJs6 Studio · 内置模拟运行时
 * 用真实 AutoJs6 API 名称搭一层可执行的模拟设备，让生成的脚本在不接手机时也能跑通、
 * 断言、复现「点签到 → 按钮变更多任务 → 打开浮层 → 逐个任务 → 空弹窗 → 收工」的链路。
 */
(function () {
  const EXIT = { __exit: true };
  const TASK_SEED = [
    { text: '浏览会场', desc: '得10京豆' },
    { text: '逛省钱频道', desc: '得10京豆', cross: true },
    { text: '加入购物车', desc: '得15京豆' }
  ];

  function createSim(opts) {
    opts = opts || {};
    const logs = [];
    let clock = 0;
    let lastNodes = [];
    const files = new Map();
    const storage = new Map();

    const st = {
      screen: 'jd.mall',
      signed: false,
      tasks: TASK_SEED.map((t, i) => Object.assign({ id: 't' + i }, t)),
      package: 'com.jingdong.app.mall',
      activity: 'MainActivity',
      clip: ''
    };

    const add = (level, msg) => {
      logs.push({ level, msg: String(msg), t: clock });
      if (logs.length > 2000) logs.shift();
    };

    const PKG = {
      'jd.mall': 'com.jingdong.app.mall', 'jd.coupon': 'com.jingdong.app.mall',
      'jd.panel': 'com.jingdong.app.mall', 'jd.popup': 'com.jingdong.app.mall',
      'jd.finance': 'com.jd.jrapp'
    };

    function buildNodes() {
      const arr = [];
      let i = 0;
      const push = o => {
        o.cls = o.cls || 'android.widget.TextView';
        o.b = o.b || [40, 110 + i * 82, 1040, 180 + i * 82];
        i++;
        arr.push(o);
        return o;
      };
      if (st.screen === 'jd.mall') {
        push({ text: '京东' });
        push({ text: '首页', clickable: true });
        push({ text: '我的', clickable: true });
        push({ text: '购物车', clickable: true });
      } else if (st.screen === 'jd.coupon') {
        push({ text: '领券中心' });
        if (st.signed) {
          push({
            text: '更多任务', desc: '更多任务', id: 'coupon-left-entry', clickable: true,
            onClick: () => { st.screen = 'jd.panel'; add('info', '打开任务浮层'); }
          });
        } else {
          push({
            text: '天天领豆', id: 'coupon-sign', clickable: true,
            onClick: () => { st.signed = true; add('info', '签到成功，入口文案即将变为「更多任务」'); }
          });
        }
        push({ text: '去逛逛', clickable: true });
      } else if (st.screen === 'jd.panel') {
        push({ text: '任务浮层' });
        if (st.tasks.length) {
          st.tasks.forEach(t => push({
            text: t.text, desc: t.desc, clickable: true,
            onClick: () => {
              st.tasks = st.tasks.filter(x => x !== t);
              st.screen = 'jd.popup';
              add('info', '完成任务：' + t.text + '（' + t.desc + '）');
            }
          }));
        } else {
          push({ text: '已完成', desc: '今日任务已完成' });
        }
      } else if (st.screen === 'jd.popup') {
        push({
          text: '', desc: '关闭', id: 'popup-close', clickable: true,
          onClick: () => { st.screen = 'jd.panel'; add('info', '关闭遮挡弹窗'); }
        });
      } else if (st.screen === 'jd.finance') {
        push({ text: '京东金融' });
        push({ text: '去完成', clickable: true });
      }
      st.package = PKG[st.screen] || st.package;
      st.activity = st.screen === 'jd.finance' ? 'JrappMainActivity' : 'BabelActivity';
      return arr;
    }

    function render() {
      lastNodes = buildNodes();
      if (opts.onRender) opts.onRender(lastNodes, st);
    }

    function wrap(n) {
      const label = () => n.text || n.desc || n.id || n.cls;
      return {
        _node: n,
        text: () => n.text || '',
        desc: () => n.desc || '',
        id: () => n.id || '',
        className: () => n.cls,
        bounds: () => ({ left: n.b[0], top: n.b[1], right: n.b[2], bottom: n.b[3] }),
        click() {
          if (!n.clickable) { add('warn', '节点不可点击：' + label()); return false; }
          add('info', '点击 → ' + label());
          try { if (n.onClick) n.onClick(); } catch (e) { add('error', '点击回调异常：' + e.message); }
          render();
          return true;
        },
        longClick() { add('info', '长按 → ' + label()); return true; },
        setText(v) {
          if (n.editable === false) { add('warn', '节点不可编辑：' + label()); return false; }
          n.text = String(v);
          add('info', '输入 → ' + label() + ' = ' + v);
          render();
          return true;
        },
        scrollForward() { add('info', '滚动 ↓ ' + label()); return true; },
        scrollBackward() { add('info', '滚动 ↑ ' + label()); return true; },
        parent() { return null; },
        children() { return []; },
        child() { return null; },
        exists() { return true; },
        toString() { return '[node ' + label() + ']'; }
      };
    }

    function makeSel(filters, label) {
      const sel = {
        _filters: filters,
        _label: label,
        _all() { return buildNodes().filter(n => filters.every(f => { try { return f(n); } catch (e) { return false; } })); },
        find() { return sel._all().map(wrap); },
        findOne(timeout) {
          const r = sel._all();
          if (r.length) return wrap(r[0]);
          add('debug', '未找到控件：' + label + (timeout ? '（已等 ' + timeout + 'ms）' : ''));
          return null;
        },
        exists() { return sel._all().length > 0; },
        waitFor() {
          const r = sel._all();
          add(r.length ? 'info' : 'warn', r.length ? '控件已出现：' + label : '等待控件超时：' + label);
          return r.length > 0;
        },
        untilFind() { return sel._all().map(wrap); },
        clickable(v) { return makeSel(filters.concat(n => !!n.clickable === !!v), label + '[clickable=' + v + ']'); },
        id(v) { return makeSel(filters.concat(n => n.id === v), label + '[id=' + v + ']'); },
        text(v) { return makeSel(filters.concat(n => n.text === v), label + '[text=' + v + ']'); }
      };
      return sel;
    }

    const F = {
      text: v => n => (n.text || '') === v,
      desc: v => n => (n.desc || '') === v,
      id: v => n => (n.id || '') === v,
      className: v => n => (n.cls || '') === v,
      textContains: v => n => (n.text || '').indexOf(v) >= 0,
      descContains: v => n => (n.desc || '').indexOf(v) >= 0,
      textMatches: v => n => new RegExp(v).test(n.text || ''),
      descMatches: v => n => new RegExp(v).test(n.desc || ''),
      idMatches: v => n => new RegExp(v).test(n.id || '')
    };

    const g = {};
    g.text = v => makeSel([F.text(v)], 'text("' + v + '")');
    g.desc = v => makeSel([F.desc(v)], 'desc("' + v + '")');
    g.id = v => makeSel([F.id(v)], 'id("' + v + '")');
    g.className = v => makeSel([F.className(v)], 'className("' + v + '")');
    g.textContains = v => makeSel([F.textContains(v)], 'textContains("' + v + '")');
    g.descContains = v => makeSel([F.descContains(v)], 'descContains("' + v + '")');
    g.textMatches = v => makeSel([F.textMatches(v)], 'textMatches("' + v + '")');
    g.descMatches = v => makeSel([F.descMatches(v)], 'descMatches("' + v + '")');
    g.idMatches = v => makeSel([F.idMatches(v)], 'idMatches("' + v + '")');
    g.classNameMatches = v => makeSel([n => new RegExp(v).test(n.cls || '')], 'classNameMatches("' + v + '")');

    g.device = {
      width: 1080, height: 2400, sdkInt: 36, brand: 'OnePlus', model: 'CPH2581',
      keepScreenOn(ms) { add('debug', '保持屏幕常亮 ' + ms + 'ms'); },
      vibrate(ms) { add('debug', '震动 ' + ms + 'ms'); },
      cancelKeepingAwake() { }
    };

    g.app = {
      launchPackage(pkg) {
        add('info', '启动应用 ' + pkg);
        if (pkg === 'com.jingdong.app.mall' || pkg === 'com.jd.jrapp') {
          st.screen = pkg === 'com.jd.jrapp' ? 'jd.finance' : 'jd.mall';
          render();
        }
        return true;
      },
      openUrl(url) {
        add('info', '打开 URL ' + url);
        if (/VAjs3vpay/.test(url)) { st.screen = 'jd.coupon'; render(); }
        return true;
      },
      kill(pkg) { add('info', '结束应用 ' + pkg); },
      getPackageName(appName) { return appName === '京东' ? 'com.jingdong.app.mall' : ''; }
    };

    g.currentPackage = () => { st.package = PKG[st.screen] || st.package; return st.package; };
    g.currentActivity = () => st.activity;
    g.waitForPackage = (pkg) => { add('debug', 'waitForPackage ' + pkg); return g.currentPackage() === pkg; };
    g.waitForActivity = (act) => { add('debug', 'waitForActivity ' + act); return st.activity === act; };

    g.sleep = ms => { clock += Number(ms) || 0; if (ms >= 1000) add('debug', 'sleep ' + ms + 'ms'); };
    g.toast = msg => { add('info', 'Toast：' + msg); if (opts.onToast) opts.onToast(String(msg)); };
    g.log = msg => add('info', String(msg));
    g.console = {
      log: msg => add('info', String(msg)),
      info: msg => add('info', String(msg)),
      warn: msg => add('warn', String(msg)),
      error: msg => add('error', String(msg)),
      show() { add('debug', '显示控制台'); },
      hide() { add('debug', '隐藏控制台'); },
      clear() { logs.length = 0; }
    };

    g.exit = () => { throw EXIT; };
    g.back = () => {
      const map = { 'jd.popup': 'jd.panel', 'jd.panel': 'jd.coupon', 'jd.finance': 'jd.panel', 'jd.coupon': 'jd.mall' };
      add('info', '按返回键（' + st.screen + ' → ' + (map[st.screen] || st.screen) + '）');
      st.screen = map[st.screen] || st.screen;
      render();
    };
    g.home = () => { add('info', '回到桌面'); st.screen = 'jd.mall'; render(); };
    g.recents = () => add('info', '任务键');
    g.press = key => {
      add('info', '按键 ' + key);
      if (key === 'back') g.back();
    };
    g.click = (x, y) => {
      add('info', '点击坐标 ' + x + ', ' + y);
      const n = lastNodes.find(n => x >= n.b[0] && x <= n.b[2] && y >= n.b[1] && y <= n.b[3] && n.clickable);
      if (n && n.onClick) { n.onClick(); render(); }
    };
    g.longClick = (x, y) => add('info', '长按坐标 ' + x + ', ' + y);
    g.swipe = (x1, y1, x2, y2, dur) => {
      add('debug', '滑动 (' + x1 + ',' + y1 + ') → (' + x2 + ',' + y2 + ') ' + (dur || 300) + 'ms');
      if (st.screen === 'jd.finance' && x1 < 20) { st.screen = 'jd.panel'; render(); }
    };
    g.gesture = (dur, points) => {
      add('debug', '手势 ' + JSON.stringify(points));
      if (Array.isArray(points) && points.length >= 2 && points[0][0] < 20) {
        if (st.screen === 'jd.finance') { st.screen = 'jd.panel'; add('info', '手势右滑切回京东'); render(); }
      }
    };

    g.setClip = t => { st.clip = String(t); add('debug', '写入剪贴板'); };
    g.getClip = () => st.clip;
    g.random = (a, b) => Math.floor(Math.random() * ((b || 1) - (a || 0)) + (a || 0));

    g.files = {
      ensureDir(p) { add('debug', 'ensureDir ' + p); },
      exists(p) { return files.has(p); },
      read(p) { return files.has(p) ? files.get(p) : null; },
      write(p, c) { files.set(p, String(c)); add('debug', '写入文件 ' + p); return true; },
      append(p, c) { files.set(p, (files.get(p) || '') + String(c)); return true; },
      listDir(p) { return Array.from(files.keys()).filter(k => k.indexOf(p) === 0); },
      remove(p) { files.delete(p); }
    };
    g.storages = {
      create(name) {
        const key = name + ':';
        return {
          get(k, d) { return storage.has(key + k) ? storage.get(key + k) : d; },
          put(k, v) { storage.set(key + k, v); return true; },
          remove(k) { storage.delete(key + k); }
        };
      }
    };
    g.http = {
      get(url) { add('debug', 'GET ' + url); return { statusCode: 200, body: { string: () => JSON.stringify({ ok: true, url }), json: () => ({ ok: true, url }) } }; },
      postJson(url, data) { add('debug', 'POST ' + url); return { statusCode: 200, body: { string: () => JSON.stringify({ ok: true, url, data }), json: () => ({ ok: true, url, data }) } }; }
    };
    g.images = {
      requestScreenCapture() { add('debug', '申请截屏权限'); return true; },
      captureScreen() { return { width: 1080, height: 2400 }; },
      read(p) { return { path: p }; },
      findImage() { return null; },
      findMultiColors() { return null; },
      save(img, p) { add('info', '截图已保存 ' + p); return true; }
    };
    g.ocr = {
      recognizeText() { return buildNodes().map(n => n.text || n.desc).filter(Boolean).join(' '); }
    };
    g.threads = {
      start(fn) { try { fn(); } catch (e) { add('error', '子线程异常：' + e.message); } },
      shutDownAll() { }
    };
    g.engines = { myEngine: () => ({ execArgv: {}, getSource: () => '' }) };

    const sim = {
      globals: g,
      logs,
      state: st,
      get clock() { return clock; },
      render,
      reset() {
        st.screen = 'jd.mall'; st.signed = false; st.tasks = TASK_SEED.map((t, i) => Object.assign({ id: 't' + i }, t));
        st.clip = ''; render();
      },
      setScreen(s) { st.screen = s; render(); }
    };

    g.$state = () => st;
    g.$setScreen = s => sim.setScreen(s);
    g.$log = () => logs.map(l => l.msg);
    g.assert = (cond, msg) => { if (!cond) throw new Error('断言失败：' + (msg || '')); };

    sim.render();
    return sim;
  }

  function execute(code, sim) {
    let fn;
    try {
      fn = new Function('__g', 'with (__g) {\n' + code + '\n}');
    } catch (e) {
      return { status: 'error', error: e };
    }
    try {
      fn(sim.globals);
      return { status: 'finished' };
    } catch (e) {
      if (e === EXIT || (e && e.__exit)) return { status: 'exited' };
      return { status: 'error', error: e };
    }
  }

  A6.runtime = {
    createSim,
    run(project, opts) {
      const sim = createSim(opts || {});
      const code = A6.gen.code(project);
      const r = execute(code, sim);
      return { status: r.status, error: r.error, logs: sim.logs, sim, code, virtualMs: sim.clock };
    },
    runTests(project, opts) {
      const code = A6.gen.code(project);
      return (project.tests || []).map(t => {
        const sim = createSim(opts || {});
        sim.globals.$run = () => {
          const r = execute(code, sim);
          if (r.error) throw r.error;
          return sim.logs;
        };
        sim.globals.$log = () => sim.logs.map(l => l.msg);
        const r = execute(t.code || '', sim);
        return {
          id: t.id, name: t.name || '未命名用例',
          ok: r.status === 'finished',
          error: r.error ? String(r.error.message || r.error) : '',
          logs: sim.logs
        };
      });
    }
  };
})();