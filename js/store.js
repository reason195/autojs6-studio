/* AutoJs6 Studio · 项目模型、树操作与持久化 */
(function () {
  const KEY = 'a6studio.project.v1';
  const MAX_UNDO = 60;
  let uid = 0;
  const nid = () => 'b_' + Date.now().toString(36) + '_' + (++uid);

  const clone = o => JSON.parse(JSON.stringify(o));

  function mkBlock(type, params) {
    const d = A6.catalog.byType(type);
    const p = {};
    (d ? d.params : []).forEach(x => p[x.key] = x.default);
    return { id: nid(), type, params: Object.assign(p, params || {}), children: {} };
  }

  function normalize(b) {
    if (!b.id) b.id = nid();
    if (!b.params) b.params = {};
    if (!b.children) b.children = {};
    return b;
  }

  function walk(list, fn, parent) {
    (list || []).forEach(b => {
      normalize(b);
      fn(b, parent);
      Object.keys(b.children).forEach(k => walk(b.children[k], fn, b));
    });
  }

  function findIn(list, id, parent) {
    for (const b of list || []) {
      if (b.id === id) return { block: b, parent };
      for (const k of Object.keys(b.children || {})) {
        const r = findIn(b.children[k], id, b);
        if (r) return r;
      }
    }
    return null;
  }

  function removeFrom(list, id) {
    for (let i = 0; i < (list || []).length; i++) {
      if (list[i].id === id) return list.splice(i, 1)[0];
      for (const k of Object.keys(list[i].children || {})) {
        const r = removeFrom(list[i].children[k], id);
        if (r) return r;
      }
    }
    return null;
  }

  function cloneBlock(b) {
    const c = clone(b);
    (function re(x) {
      x.id = nid();
      if (x.children) Object.keys(x.children).forEach(k => x.children[k].forEach(re));
    })(c);
    return c;
  }

  function count(list) {
    return (list || []).reduce((n, b) => n + 1 + Object.keys(b.children || {}).reduce((m, k) => m + count(b.children[k]), 0), 0);
  }

  function emptyProject(name) {
    return {
      name: name || '未命名项目',
      root: { id: 'root', type: 'program', params: {}, children: { body: [] } },
      modules: [],
      tests: [],
      bridge: {
        url: (A6.config && A6.config.defaultUrl) || 'http://127.0.0.1:8765',
        devices: (A6.config && Array.isArray(A6.config.devices)) ? JSON.parse(JSON.stringify(A6.config.devices)) : []
      },
      createdAt: Date.now()
    };
  }

  /* 示例项目：复原分享会话里跑通的京东领券中心主流程 */
  function exampleProject() {
    const p = emptyProject('京东领券中心 · 实战示例');
    const body = p.root.children.body;
    const add = (type, params, kids) => {
      const b = mkBlock(type, params);
      if (kids) b.children = kids;
      body.push(b);
      return b;
    };
    add('comment', { text: '京东领券中心主流程：进入 → 签到 → 打开浮层 → 逐任务执行 → 关弹窗 → 收工判定' });
    add('keepScreenOn', { ms: 1800000 });
    add('consoleShow');
    add('jdEnter');
    add('jdSign');
    add('jdOpenPanel');
    const loopTask = mkBlock('jdDoTask', { taskVar: 'task', stay: 7000 });
    add('jdTaskLoop', { taskVar: 'task', max: 8 }, { body: [loopTask] });
    add('jdClosePopup');
    add('jdFinishGuard', { rounds: 12, zero: 3 });
    add('logExpr', { expr: '"流程结束"' });

    p.modules = [{
      id: 'mod_sample', name: '快速签到', desc: '进入领券中心并完成签到，返回布尔值',
      args: 'pkg',
      children: { body: [mkBlock('jdEnter'), mkBlock('jdSign'), mkBlock('assertBlock', { cond: 'true', msg: '签到标志物' })] }
    }];

    p.tests = [
      {
        id: 't1', name: '签到按钮：点击后出现「更多任务」',
        code: "$setScreen('jd.coupon');\nvar btn = text('天天领豆').findOne(1000);\nassert(btn, '应能找到签到按钮');\nbtn.click();\nassert(desc('更多任务').exists(), '点击后入口文案应变为「更多任务」');"
      },
      {
        id: 't2', name: '主流程：能跑完并输出收工判定',
        code: "$setScreen('jd.mall');\n$run();\nvar logs = $log().join('\\n');\nassert(logs.indexOf('本轮处理任务') >= 0, '应处理到京豆任务');\nassert(logs.indexOf('收工') >= 0, '应输出收工判定');"
      },
      {
        id: 't3', name: '0 任务时不误判：下滑扫描后收工',
        code: "$setScreen('jd.panel');\n$state().tasks = [];\n$run();\nassert($log().join('\\n').indexOf('收工') >= 0, '空任务应经扫描后收工');"
      }
    ];
    return p;
  }

  const state = {
    project: emptyProject(),
    selected: null,
    scope: { kind: 'root' },
    undo: [],
    undoLabel: ''
  };

  function load() {
    try {
      const raw = localStorage.getItem(KEY);
      if (!raw) { state.project = exampleProject(); return; }
      const p = JSON.parse(raw);
      if (!p || !p.root) throw new Error('bad project');
      p.modules = p.modules || []; p.tests = p.tests || [];
      walk([p.root], () => { });
      p.modules.forEach(m => walk(m.children.body, () => { }));
      state.project = p;
    } catch (e) {
      state.project = exampleProject();
    }
  }

  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(state.project)); } catch (e) { }
  }

  function snapshot(label) {
    state.undo.push({ label: label || '编辑', data: JSON.stringify(state.project) });
    if (state.undo.length > MAX_UNDO) state.undo.shift();
  }

  function undo() {
    const last = state.undo.pop();
    if (!last) return null;
    state.project = JSON.parse(last.data);
    state.selected = null;
    return last.label;
  }

  function scopeList() {
    if (state.scope.kind === 'module') {
      const m = state.project.modules.find(x => x.id === state.scope.id);
      return m ? m.children.body : null;
    }
    return state.project.root.children.body;
  }

  function currentModule() {
    return state.scope.kind === 'module' ? state.project.modules.find(x => x.id === state.scope.id) : null;
  }

  function blockById(id) {
    const r = findIn(state.project.root.children.body, id);
    if (r) return r;
    for (const m of state.project.modules) {
      const q = findIn(m.children.body, id);
      if (q) return q;
    }
    return null;
  }

  A6.store = {
    state, nid, mkBlock, clone, walk, findIn, removeFrom, cloneBlock, count,
    emptyProject, exampleProject, load, save, snapshot, undo, scopeList, currentModule, blockById,
    scopeLabel() {
      const m = currentModule();
      return m ? '模块：' + m.name : '主程序';
    }
  };
})();