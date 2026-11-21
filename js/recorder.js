/* AutoJs6 Studio · 真机录制（方案 1：设备侧监听脚本）
 * 流程：把一段监听脚本 script_run 到手机上（不等待结束）→ 脚本把触摸/按键事件写成 JSONL →
 * IDE 轮询 files_read 取回新行 → 把事件转成积木（能用唯一选择器就写 clickNode，否则退坐标）。
 * 依赖的 MCP 工具：script_run / script_stop / files_read。
 */
(function () {
  const S = A6.store;
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));
  const OUT_PATH = 'studio-record.jsonl';

  /* 设备侧脚本（Rhino，ES5）：写 JSONL，尽量自证能力 */
  const DEVICE_SCRIPT = [
    'var ARGS = (engines.myEngine().execArgv && engines.myEngine().execArgv) || {};',
    'var OUT = ARGS.out || "/sdcard/studio-record.jsonl";',
    'var MAX = Number(ARGS.max || 300);',
    'var n = 0;',
    'try { files.ensureDir(OUT); } catch (e) {}',
    'function emit(o) {',
    '  if (n >= MAX) return;',
    '  n++;',
    '  try { files.append(OUT, JSON.stringify(o) + "\\n"); } catch (e) { console.error("emit " + e); }',
    '}',
    'function cnt(f) { try { var r = f.find(); return r ? r.length : 0; } catch (e) { return -1; } }',
    'function nodeAt(x, y) {',
    '  try {',
    '    var list = clickable(true).boundsContains(x - 4, y - 4, x + 4, y + 4).find();',
    '    if (!list || !list.length) return null;',
    '    var best = null, area = 1e12;',
    '    for (var i = 0; i < list.length; i++) {',
    '      var bd = list[i].bounds();',
    '      var ar = (bd.right - bd.left) * (bd.bottom - bd.top);',
    '      if (ar < area) { area = ar; best = list[i]; }',
    '    }',
    '    if (!best) return null;',
    '    var b2 = best.bounds();',
    '    var txt = String(best.text() || "");',
    '    var dsc = String(best.desc() || "");',
    '    var vid = String(best.id() || "");',
    '    /* 命中数按 id → text → textContains → desc 依次短路：典型情况只查 1 次。',
    '       触摸回调里每多一次全屏查找，都会加重主线程负担（实测会占住 MCP 服务） */',
    '    var cs = { id: -1, text: -1, textContains: -1, desc: -1 };',
    '    if (vid) cs.id = cnt(id(vid));',
    '    if (cs.id !== 1 && txt) cs.text = cnt(text(txt));',
    '    if (cs.id !== 1 && cs.text !== 1 && txt) cs.textContains = cnt(textContains(txt));',
    '    if (cs.id !== 1 && cs.text !== 1 && cs.textContains !== 1 && dsc) cs.desc = cnt(desc(dsc));',
    '    return {',
    '      text: txt, desc: dsc, id: vid, className: String(best.className() || ""),',
    '      bounds: { left: b2.left, top: b2.top, right: b2.right, bottom: b2.bottom },',
    '      counts: cs',
    '    };',
    '  } catch (e) { return null; }',
    '}',
    'var pts = [];',
    'var timer = null;',
    'function flush() {',
    '  timer = null;',
    '  if (!pts.length) return;',
    '  var a = pts[0], b = pts[pts.length - 1];',
    '  var dx = b.x - a.x, dy = b.y - a.y;',
    '  var dist = Math.sqrt(dx * dx + dy * dy);',
    '  var pkg = "";',
    '  try { pkg = currentPackage(); } catch (e) {}',
    '  if (pts.length >= 2 && dist >= 40) {',
    '    emit({ type: "swipe", t: a.t, dur: Math.max(200, b.t - a.t), x1: a.x, y1: a.y, x2: b.x, y2: b.y, pkg: pkg });',
    '  } else {',
    '    emit({ type: "tap", t: a.t, x: b.x, y: b.y, pkg: pkg, node: nodeAt(b.x, b.y) });',
    '  }',
    '  pts = [];',
    '}',
    'function onPoint(p) {',
    '  if (!p) return;',
    '  var px = Number(p.x) || 0, py = Number(p.y) || 0;',
    '  /* 实测：部分机型触摸事件以 1/100 像素上报，超出屏幕就归一化 */',
    '  if (px > device.width) px = px / 100;',
    '  if (py > device.height) py = py / 100;',
    '  pts.push({ x: Math.round(px), y: Math.round(py), t: Date.now() });',
    '  if (timer) clearTimeout(timer);',
    '  timer = setTimeout(flush, 420);',
    '}',
    'var cap = { observeTouch: typeof events.observeTouch, onTouch: typeof events.onTouch, observeKey: typeof events.observeKey };',
    'emit({ type: "start", t: Date.now(), cap: cap, path: OUT, screen: { w: device.width, h: device.height }, pkg: (function () { try { return currentPackage(); } catch (e) { return ""; } })() });',
    'try { toast("录制已开始：操作手机即可，按音量下键停止"); } catch (e) {}',
    'var ok = true, why = "";',
    'try {',
    '  if (typeof events.observeTouch === "function") events.observeTouch();',
    '  if (typeof events.onTouch === "function") events.onTouch(onPoint);',
    '  else if (typeof events.on === "function") events.on("touch", onPoint);',
    '  else ok = false;',
    '} catch (e) { ok = false; why = String(e); }',
    'if (!ok) emit({ type: "error", t: Date.now(), message: why || "touch listener unavailable" });',
    'try {',
    '  if (typeof events.observeKey === "function") {',
    '    events.observeKey();',
    '    events.onKeyDown("back", function () { emit({ type: "key", key: "back", t: Date.now() }); });',
    '    events.onKeyDown("home", function () { emit({ type: "key", key: "home", t: Date.now() }); });',
    '  }',
    '} catch (e) {}',
    'try { events.onKeyDown("volume_down", function () { try { toast("录制已停止"); } catch (e) {} exit(); }); } catch (e) {}',
    'setInterval(function () {}, 1000);'
  ].join('\n');

  /* ---------- 状态 ---------- */
  let st = {
    running: false,
    executionId: null,
    events: [],
    inserted: 0,
    note: '',
    timer: null,
    lines: 0,
    fails: 0
  };

  function ensureDom() {
    if ($('recorder')) return;
    const wrap = document.createElement('div');
    wrap.id = 'recorder';
    wrap.className = 'audit-mask';
    wrap.innerHTML = ''
      + '<div class="audit-panel">'
      + '  <div class="audit-head">'
      + '    <b>真机录制</b><span id="recMeta" class="audit-meta">—</span>'
      + '    <span class="audit-spacer"></span>'
      + '    <button id="recStart" class="btn primary xs">开始录制</button>'
      + '    <button id="recStop" class="btn xs" disabled>停止</button>'
      + '    <button id="recGen" class="btn xs" disabled>生成积木</button>'
      + '    <button id="recFetch" class="btn ghost xs" title="录制脚本停掉后，直接读取手机上的记录文件并生成积木">读取并生成</button>'
      + '    <button id="recExport" class="btn ghost xs" title="下载可直接在手机上运行的录制脚本">导出脚本</button>'
      + '    <button id="recImport" class="btn ghost xs" title="导入手机上录出的 JSONL 文件">导入文件</button>'
      + '    <input id="recFile" type="file" accept=".jsonl,.txt,.log,.json" style="display:none">'
      + '    <button id="recClear" class="btn ghost xs">清空</button>'
      + '    <button id="recClose" class="btn ghost xs">✕</button>'
      + '  </div>'
      + '  <div id="recList" class="audit-list scroll"></div>'
      + '  <div id="recFoot" class="audit-foot">点「开始录制」把监听脚本推到手机，然后在你手机上操作（点按、滑动）。注意：AutoJs6 里脚本一旦常驻触摸监听，同进程的 MCP 服务就不再应答（已实测三次），所以录制期间不读事件——操作完请在手机上停止脚本（或点「停止」），再点「读取并生成」一次读回并转成积木。脚本要求无障碍服务已开启；触摸监听拿不到长按时长，长按会按「点击」记录。若在手机上点了这里却一条都没有，多半是系统没把触摸分发给无障碍服务（触摸探索关闭、或同时开了多个无障碍服务），此时改用 ⌖ 拾取器或「导入文件」更快。</div>'
      + '</div>';
    document.body.appendChild(wrap);
    $('recClose').onclick = () => close();
    $('recStart').onclick = () => start();
    $('recStop').onclick = () => stop();
    $('recGen').onclick = () => generate();
    $('recFetch').onclick = () => fetchAndGenerate();
    $('recClear').onclick = () => reset();
    $('recExport').onclick = () => exportScript();
    $('recImport').onclick = () => $('recFile').click();
    $('recFile').onchange = ev => importFile(ev.target.files && ev.target.files[0]);
    wrap.addEventListener('click', ev => { if (ev.target === wrap) close(); });
  }

  function label(e) {
    if (e.type === 'start') return '开始录制（' + (e.screen ? e.screen.w + '×' + e.screen.h : '未知尺寸') + '）';
    if (e.type === 'error') return '录制脚本报错：' + (e.message || '');
    if (e.type === 'key') return '按键 ' + e.key;
    if (e.type === 'swipe') return '滑动 (' + e.x1 + ',' + e.y1 + ') → (' + e.x2 + ',' + e.y2 + ') · ' + e.dur + 'ms';
    if (e.type === 'tap') {
      const n = e.node;
      const what = n ? (n.text || n.desc || (n.id ? '#' + String(n.id).split('/').pop() : n.className)) : '';
      const sel = pickSelector(e);
      return '点击 (' + e.x + ',' + e.y + ')' + (what ? ' · ' + what : '') + (sel ? ' · ' + fnName(sel.by) + '(' + JSON.stringify(sel.value) + ')' : ' · 无唯一选择器，写坐标');
    }
    return e.type;
  }

  function chip(e) {
    if (e.type === 'start') return '<span class="ar-chip ok">开始</span>';
    if (e.type === 'error') return '<span class="ar-chip bad">报错</span>';
    if (e.type === 'key') return '<span class="ar-chip skip">按键</span>';
    if (e.type === 'swipe') return '<span class="ar-chip">滑动</span>';
    if (e.type === 'tap') {
      return '<span class="ar-chip ' + (pickSelector(e) ? 'ok' : 'warn') + '">点击</span>';
    }
    return '<span class="ar-chip idle">' + esc(e.type) + '</span>';
  }

  function renderList() {
    const box = $('recList');
    if (!box) return;
    const list = st.events.slice(0, 200);
    if (!list.length) {
      box.innerHTML = '<div class="audit-empty">' + (st.running ? '已开始，等你操作手机…' : '还没录到内容。点「开始录制」，然后在手机上操作。') + '</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    list.forEach((e, i) => {
      const row = document.createElement('div');
      row.className = 'audit-row';
      row.innerHTML = '<div class="ar-top">' + chip(e) + '<span class="ar-name">#' + (i + 1) + '</span>'
        + (e.pkg ? '<span class="ar-scope">' + esc(e.pkg) + '</span>' : '') + '</div>'
        + '<div class="ar-sel">' + esc(label(e)) + '</div>';
      frag.appendChild(row);
    });
    box.innerHTML = '';
    box.appendChild(frag);
  }

  function renderMeta() {
    const usable = st.events.filter(e => e.type === 'tap' || e.type === 'swipe' || e.type === 'key').length;
    if ($('recMeta')) $('recMeta').textContent = st.running ? ('录制中 · ' + usable + ' 段操作') : (usable ? (usable + ' 段操作 · 未生成或已生成') : '—');
    if ($('recStart')) $('recStart').disabled = st.running;
    if ($('recStop')) $('recStop').disabled = !st.running;
    if ($('recGen')) $('recGen').disabled = st.running || usable === 0;
    if ($('recGen')) $('recGen').textContent = st.inserted ? ('生成积木（新 ' + Math.max(0, usable - st.inserted) + '）') : '生成积木';
    if ($('recFetch')) $('recFetch').disabled = st.running;
  }

  /* ---------- 事件 → 积木 ---------- */
  const FN = { id: 'id', text: 'text', textContains: 'textContains', desc: 'desc' };
  function fnName(by) { return FN[by] || 'text'; }

  /* 设备侧已算好每个候选的命中数：优先 id，其次 text，再退 textContains / desc */
  function pickSelector(e) {
    const n = e && e.node;
    if (!n || !n.counts) return null;
    const order = ['id', 'text', 'textContains', 'desc'];
    for (const k of order) {
      const c = n.counts[k];
      if (c === 1) {
        const v = k === 'id' ? n.id : k === 'desc' ? n.desc : n.text;
        if (v) return { by: k, value: String(v) };
      }
    }
    return null;
  }

  function blockOf(e) {
    if (e.type === 'tap') {
      const sel = pickSelector(e);
      if (sel) return S.mkBlock('clickNode', { by: sel.by, value: sel.value });
      return S.mkBlock('coordClick', { x: e.x, y: e.y });
    }
    if (e.type === 'swipe') {
      const dur = Math.min(2000, Math.max(200, Number(e.dur) || 400));
      return S.mkBlock('swipe', { x1: e.x1, y1: e.y1, x2: e.x2, y2: e.y2, dur: dur });
    }
    if (e.type === 'key') return S.mkBlock('pressKey', { key: e.key });
    return null;
  }

  function generate() {
    const useful = st.events.filter(e => e.type === 'tap' || e.type === 'swipe' || e.type === 'key');
    const fresh = useful.slice(st.inserted);
    if (!fresh.length) { A6.ui.toast('没有新操作可以生成'); return; }
    const m = S.currentModule();
    const body = m ? m.children.body : S.state.project.root.children.body;
    S.snapshot('录制生成积木');
    let n = 0;
    fresh.forEach(e => {
      const b = blockOf(e);
      if (b) { body.push(b); n++; }
    });
    st.inserted = useful.length;
    S.save();
    A6.ui.commit('录制生成积木');
    renderMeta();
    $('recFoot').textContent = '已生成 ' + n + ' 个积木，可 Ctrl+Z 撤销。看不懂某一步就在画布上点它，右侧代码会高亮对应行。';
    A6.ui.toast('已生成 ' + n + ' 个积木');
  }

  /* ---------- 录制控制 ---------- */
  /* 有些机型触摸事件以 1/100 像素上报（实测魅族：tap 7606,30482 对应屏幕 1080x2376）；
     导入旧文件时按 start 事件里的屏幕尺寸再归一一次，保证坐标可用 */
  function normalizeEvents(evs) {
    const start = evs.find(e => e.type === 'start');
    const w = start && start.screen ? Number(start.screen.w) || 0 : 0;
    const h = start && start.screen ? Number(start.screen.h) || 0 : 0;
    const fix = (v, max) => {
      let n = Number(v) || 0;
      if (max && n > max) n = n / 100;
      return Math.round(n);
    };
    evs.forEach(e => {
      if (e.type === 'tap') { e.x = fix(e.x, w); e.y = fix(e.y, h); }
      if (e.type === 'swipe') {
        e.x1 = fix(e.x1, w); e.y1 = fix(e.y1, h);
        e.x2 = fix(e.x2, w); e.y2 = fix(e.y2, h);
        const dx = e.x2 - e.x1, dy = e.y2 - e.y1;
        if (Math.sqrt(dx * dx + dy * dy) < 40) e.dur = Math.min(Number(e.dur) || 400, 300);
      }
    });
    return evs;
  }

  function parseLines(text) {
    const out = [];
    String(text || '').split('\n').forEach(l => {
      l = l.trim();
      if (!l) return;
      try { const o = JSON.parse(l); if (o && o.type) out.push(o); } catch (e) { /* 半行跳过 */ }
    });
    return out;
  }

  async function tick() {
    if (!st.running) return;
    try {
      const r = await A6.bridge.filesRead(OUT_PATH, { encoding: 'utf-8' });
      const text = r && (r.content != null ? r.content : r.text != null ? r.text : r.data);
      if (typeof text === 'string') {
        st.fails = 0;
        const evs = normalizeEvents(parseLines(text));
        if (evs.length !== st.lines) {
          st.events = evs;
          st.lines = evs.length;
          renderList(); renderMeta();
          const bad = evs.find(e => e.type === 'error');
          if (bad) $('recFoot').textContent = '录制脚本报错：' + (bad.message || '') + '（多半是触摸监听不可用，或无障碍服务没开）';
        }
      }
    } catch (e) {
      st.note = e.message || String(e);
      st.fails = (st.fails || 0) + 1;
      /* 手机被录制脚本/无障碍查询占住时会"端口在听但不回声"；别让界面无限转圈 */
      if (e.code === 'TIMEOUT' && st.fails >= 3) {
        st.running = false;
        if (st.timer) { clearTimeout(st.timer); st.timer = null; }
        renderList(); renderMeta();
        $('recFoot').textContent = '手机 MCP 连续 ' + st.fails + ' 次超时，已停止轮询。'
          + '多半是录制脚本或无障碍查询把 AutoJs6 主线程占住了：在手机上按音量下键停止录制，重启 MCP Server，'
          + '再点「读取并生成」把已经录到的操作取回来（记录文件在手机里，不会丢）。';
        A6.ui.toast('手机 MCP 无响应，已停止轮询');
        return;
      }
      if ($('recMeta')) $('recMeta').textContent = '录制中 · 读事件失败：' + st.note.slice(0, 48);
    }
    if (st.running) st.timer = setTimeout(tick, 900);
  }

  async function start() {
    if (st.running) return;
    st.running = true; st.events = []; st.lines = 0; st.inserted = 0; st.note = ''; st.fails = 0;
    renderList(); renderMeta();
    $('recFoot').textContent = '正在把监听脚本推到手机…';
    try {
      const r = await A6.bridge.scriptRun(DEVICE_SCRIPT, {
        name: 'a6studio-record',
        waitForCompletion: false,
        arguments: { out: OUT_PATH, max: 300 }
      });
      st.executionId = (r && (r.executionId != null ? r.executionId : r.id)) || null;
      $('recFoot').textContent = '已开始（executionId=' + st.executionId + '）。现在在手机上操作；录制期间不与手机通信，操作完在手机上停止脚本，再点「读取并生成」。';
      A6.ui.toast('开始录制，请在手机上操作');
      /* 不轮询：脚本常驻触摸监听时 AutoJs6 的 MCP 服务不会应答（实测三次），录制期间任何 files_read 只会超时；等停止后一次读回即可。 */
    } catch (e) {
      st.running = false;
      renderMeta();
      const msg = String(e.message || e);
      let hint = '（确认 MCP 已连到要录制的那台手机）';
      if (/working directory/i.test(msg)) hint = '魅族上 AutoJs6 的工作目录不存在：在手机内部存储根目录新建名为「脚本」的文件夹，或到 AutoJs6 设置里把工作目录改成已有目录，然后重开录制。';
      $('recFoot').textContent = '启动失败：' + msg + ' ' + hint;
      A6.ui.toast('录制启动失败');
    }
  }

  async function stop() {
    st.running = false;
    if (st.timer) { clearTimeout(st.timer); st.timer = null; }
    let stopped = false;
    if (st.executionId != null) {
      try { await A6.bridge.scriptStop(st.executionId, 4000); stopped = true; } catch (e) { }
    }
    try { renderList(); renderMeta(); } catch (e) { }
    const foot = $('recFoot');
    if (foot) foot.textContent = stopped
      ? '已通知手机停止脚本。点「读取并生成」把录到的操作转成积木。'
      : '没能通知手机停止脚本（录制期间 MCP 不应答）：请到手机上从 AutoJs6 停止脚本，然后点「读取并生成」。';
  }

  async function tickOnce() {
    const r = await A6.bridge.filesRead(OUT_PATH, { encoding: 'utf-8' });
    const text = r && (r.content != null ? r.content : r.text);
    if (typeof text === 'string') {
      const evs = normalizeEvents(parseLines(text));
      st.events = evs; st.lines = evs.length;
    }
    return st.events.length;
  }

  /* 录制脚本已经停了（或刚恢复连接）时，直接读文件 + 生成，省掉"停止→等轮询"这一步 */
  async function fetchAndGenerate() {
    /* 录制中也能读：先收尾本地状态（手机上是否仍在跑，由这次读取的结果说话） */
    if (st.running) { st.running = false; if (st.timer) { clearTimeout(st.timer); st.timer = null; } }
    const foot = $('recFoot');
    if (foot) foot.textContent = '正在读取手机上的 ' + OUT_PATH + '…';
    try {
      const n = await tickOnce();
      if (!n) {
        if (foot) foot.textContent = '手机上的 ' + OUT_PATH + ' 里还没有内容（这次录制没有写出事件，或工作目录不对）。';
        A6.ui.toast('没读到事件');
        return;
      }
      try { renderList(); renderMeta(); } catch (e2) { }
      generate();
    } catch (e) {
      const msg = String(e.message || e);
      if (foot) foot.textContent = (e.code === 'TIMEOUT' ? '手机 MCP 卡住了：' : '读取失败：') + msg;
      A6.ui.toast(e.code === 'TIMEOUT' ? '手机 MCP 无响应' : '读取失败');
    }
  }

  /* 离线录制：把同一份设备脚本导出成 .js，用户在手机上直接用 AutoJs6 打开运行 */
  function exportScript() {
    const banner = `/* AutoJs6 Studio · 录制脚本（离线版）
 * 用法：把本文件放到手机，用 AutoJs6 打开并运行；操作手机即可录制。
 * 停止：按音量下键（或从 AutoJs6 悬浮窗停止）。
 * 产出：/sdcard/studio-record.jsonl（一行一事件）。
 * 回到 Studio：「录制」面板 →「导入文件」选这个 jsonl，再点「生成积木」。
 */
`;
    const blob = new Blob([banner + DEVICE_SCRIPT], { type: 'text/javascript;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'a6studio-record.js';
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
    A6.ui.toast('已导出 a6studio-record.js，放到手机上用 AutoJs6 运行');
  }

  function importText(text) {
    const evs = normalizeEvents(parseLines(text));
    if (!evs.length) { A6.ui.toast('这个文件里没有可识别的事件'); return 0; }
    st.events = evs;
    st.lines = evs.length;
    st.inserted = 0;
    renderList(); renderMeta();
    const start = evs.find(e => e.type === 'start');
    $('recFoot').textContent = '已导入 ' + evs.length + ' 行事件'
      + (start && start.path ? '（手机上的文件：' + start.path + '）' : '')
      + '，点「生成积木」把它们变成积木。';
    A6.ui.toast('已导入 ' + evs.length + ' 条事件');
    return evs.length;
  }

  function importFile(file) {
    if (!file) return;
    const fr = new FileReader();
    fr.onload = () => { try { importText(String(fr.result || '')); } catch (e) { A6.ui.toast('导入失败：' + e.message); } };
    fr.readAsText(file);
  }

  function reset() {
    st.events = []; st.lines = 0; st.inserted = 0; st.note = '';
    renderList(); renderMeta();
    $('recFoot').textContent = '已清空列表（手机上的旧记录文件不影响下一次生成，生成只认列表里的新内容）。';
  }

  function open() {
    ensureDom();
    $('recorder').classList.add('open');
    renderList(); renderMeta();
  }
  function close() {
    const el = $('recorder');
    if (el) el.classList.remove('open');
  }
  function isOpen() {
    const el = $('recorder');
    return !!(el && el.classList.contains('open'));
  }

  function bindToolbar() {
    const bar = document.querySelector('.topbar .actions');
    if (!bar || $('btnRec')) return;
    const b = document.createElement('button');
    b.id = 'btnRec';
    b.className = 'btn';
    b.title = '真机录制：在手机上操作，转成积木（F9）';
    b.textContent = '● 录制';
    b.onclick = () => open();
    const audit = $('btnAudit');
    if (audit && audit.parentNode === bar) bar.insertBefore(b, audit.nextSibling);
    else bar.appendChild(b);
  }

  document.addEventListener('keydown', ev => {
    if (ev.key === 'F9') { ev.preventDefault(); open(); }
    else if (ev.key === 'Escape' && isOpen()) close();
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bindToolbar);
  else bindToolbar();

  A6.recorder = {
    open, close, isOpen, start, stop, generate, reset,
    tick, fetchAndGenerate, events: () => st.events, state: () => st,
    exportScript, importText, importFile, normalizeEvents,
    script: () => DEVICE_SCRIPT, pickSelector, blockOf
  };
})();