/* AutoJs6 Studio · 应用装配层 */
(function () {
  const S = A6.store;
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));
  let diagTimer = null;
  let lastRun = null;
  let lastSim = null;

  /* ---------- 通用 UI ---------- */
  let toastTimer = null;
  function toast(msg) {
    const t = $('toast');
    t.textContent = msg; t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 1800);
  }
  function setHint(txt) { $('scopeHint').textContent = txt || ''; }
  function setBadge(id, cls, txt) {
    const b = $(id); if (!b) return;
    b.className = 'badge ' + (cls || 'idle'); b.textContent = txt;
  }
  function logLine(box, level, msg, t) {
    const d = document.createElement('div');
    d.className = 'l ' + level;
    d.innerHTML = (t != null ? '<span class="ts">[' + t + ']</span>' : '') + esc(msg);
    box.appendChild(d); box.scrollTop = box.scrollHeight;
  }
  function renderLogs(box, logs) {
    box.innerHTML = '';
    (logs || []).forEach(l => logLine(box, l.level, l.msg, l.t));
    if (!(logs || []).length) box.appendChild(Object.assign(document.createElement('div'), { className: 'l debug', textContent: '暂无日志' }));
  }

  /* ---------- 代码 / 统计 ---------- */
  function subtreeIds(id) {
    const set = new Set();
    const f = S.blockById(id);
    if (!f) return set;
    set.add(id);
    Object.keys(f.block.children || {}).forEach(k => S.walk(f.block.children[k] || [], b => set.add(b.id)));
    return set;
  }

  /* 选中积木 → 高亮它生成的代码行（含子积木） */
  function highlightCode(id, scroll) {
    const nodes = Array.prototype.slice.call(document.querySelectorAll('#codeOut .cl'));
    nodes.forEach(n => n.classList.remove('hl'));
    if (!id) return;
    const idx = nodes.findIndex(n => n.classList.contains('mk') && n.dataset.id === id);
    if (idx < 0) return;
    const sub = subtreeIds(id);
    let end = nodes.length - 1;
    for (let i = idx + 1; i < nodes.length; i++) {
      const n = nodes[i];
      if (n.classList.contains('mk') && !sub.has(n.dataset.id)) { end = i - 1; break; }
    }
    for (let i = idx; i <= end; i++) if (!nodes[i].classList.contains('mk')) nodes[i].classList.add('hl');
    if (scroll !== false) {
      const first = nodes.slice(idx, end + 1).find(n => !n.classList.contains('mk'));
      if (first) first.scrollIntoView({ block: 'nearest' });
    }
  }

  function refreshCode() {
    const marked = A6.gen.code(S.state.project, { markers: true });
    const out = $('codeOut');
    const top = out.scrollTop;
    out.innerHTML = marked.split('\n').map(l => {
      const m = /^[ \t]*\/\/@([A-Za-z0-9_]+)$/.exec(l);
      if (m) return '<div class="cl mk" data-id="' + m[1] + '"></div>';
      return '<div class="cl">' + (l ? esc(l) : '&nbsp;') + '</div>';
    }).join('');
    out.scrollTop = top;
    const clean = A6.gen.code(S.state.project);
    $('codeInfo').textContent = clean.split('\n').length + ' 行 · ' + new Blob([clean]).size + ' B';
    highlightCode(S.state.selected, false);
  }

  function scheduleDiag() {
    clearTimeout(diagTimer);
    diagTimer = setTimeout(() => {
      const issues = A6.diag.scan(S.state.project);
      const err = issues.filter(i => i.level === 'error').length;
      const warn = issues.filter(i => i.level === 'warn').length;
      setBadge('diagSummary', err ? 'err' : (warn ? 'warn' : 'ok'),
        err ? err + ' 错误 · ' + warn + ' 警告' : (warn ? warn + ' 警告' : '无问题'));
      if ($('pane-diag').classList.contains('active')) renderDiag(issues);
    }, 350);
  }

  /* ---------- 作用域 ---------- */
  function renderScopeTabs() {
    const box = $('scopeTabs');
    box.innerHTML = '';
    const mk = (label, active, fn) => {
      const b = document.createElement('button');
      b.className = 'tab' + (active ? ' active' : '');
      b.textContent = label;
      b.onclick = fn;
      box.appendChild(b);
    };
    mk('主程序', S.state.scope.kind === 'root', () => { S.state.scope = { kind: 'root' }; S.state.selected = null; renderAll(); });
    (S.state.project.modules || []).forEach(m => {
      mk(m.name || '未命名模块', S.state.scope.kind === 'module' && S.state.scope.id === m.id, () => {
        S.state.scope = { kind: 'module', id: m.id }; S.state.selected = null; renderAll();
      });
    });
  }

  /* ---------- 整体渲染 ---------- */
  function renderAll() {
    $('projectName').value = S.state.project.name || '';
    renderScopeTabs();
    A6.editor.renderCanvas();
    A6.editor.renderPalette($('paletteSearch').value);
    refreshCode();
    renderTests();
    scheduleDiag();
  }

  function commit(label) {
    A6.editor.renderCanvas();
    renderScopeTabs();
    refreshCode();
    scheduleDiag();
    S.save();
  }

  /* ---------- 模拟器 ---------- */
  function renderPhone(nodes, st) {
    const scr = $('simScreen');
    $('simPkg').textContent = st.package;
    scr.innerHTML = '';
    nodes.forEach(n => {
      const d = document.createElement('div');
      d.className = 'pn' + (n.clickable ? ' click' : '');
      d.innerHTML = '<span>' + esc(n.text || '✕') + '</span>' + (n.desc ? '<span class="sub">' + esc(n.desc) + '</span>' : '');
      if (n.clickable && n.onClick) {
        d.onclick = () => { n.onClick(); if (lastSim) lastSim.render(); };
      }
      scr.appendChild(d);
    });
    if (!nodes.length) scr.innerHTML = '<div class="pn">（空界面）</div>';
  }

  function showBubble(msg) {
    const scr = $('simScreen');
    const b = document.createElement('div');
    b.className = 'toast-bubble'; b.textContent = msg;
    scr.appendChild(b);
    setTimeout(() => b.remove(), 2400);
  }

  async function run() {
    $('runLog').innerHTML = '';
    setBadge('runStatus', 'run', '运行中');
    showTab('run');
    if ($('optRealDevice').checked) return runOnDevice();
    try {
      const res = A6.runtime.run(S.state.project, { onRender: renderPhone, onToast: showBubble });
      lastRun = res; lastSim = res.sim;
      renderLogs($('runLog'), res.logs);
      if (res.status === 'error') {
        setBadge('runStatus', 'err', '异常');
        logLine($('runLog'), 'error', '异常：' + (res.error && res.error.message || res.error));
      } else if (res.status === 'exited') {
        setBadge('runStatus', 'warn', '已退出');
      } else {
        setBadge('runStatus', 'ok', '完成 · 模拟 ' + Math.round(res.virtualMs / 1000) + 's');
      }
      applyRuntimeDiag(res);
    } catch (e) {
      setBadge('runStatus', 'err', '无法运行');
      logLine($('runLog'), 'error', '运行时错误：' + e.message);
    }
  }

  async function runOnDevice() {
    const code = A6.gen.code(S.state.project);
    $('deviceLog').innerHTML = '';
    try {
      applyBridgeCfg();
      const r = await A6.bridge.runScript(code, (S.state.project.name || 'a6studio') + '_' + Date.now());
      logLine($('deviceLog'), 'sys', '真机返回：' + JSON.stringify(r).slice(0, 800));
      const tail = await A6.bridge.consoleTail(120);
      (tail.entries || tail.lines || []).forEach(l => logLine($('deviceLog'), (l.level || 'info'), (l.message || l.msg || JSON.stringify(l))));
      setBadge('runStatus', r && r.status === 'error' ? 'err' : 'ok', '真机 · ' + (r && r.status || '完成'));
      $('deviceChip').className = 'chip ok';
      $('deviceChip').textContent = '真机：已连接';
    } catch (e) {
      logLine($('deviceLog'), 'error', '真机调用失败：' + e.message);
      logLine($('deviceLog'), 'info', '常见原因：MCP 未开启 / 地址不对 / 浏览器跨域被拦。可先跑本地模拟，或启动包内 bridge 后再连。');
      setBadge('runStatus', 'err', '真机不可用');
      $('deviceChip').className = 'chip err';
      $('deviceChip').textContent = '真机：连接失败';
    }
  }

  function applyRuntimeDiag(run) {
    const issues = A6.diag.fromRun(run);
    if (!issues.length) return;
    const pane = $('diagList');
    if ($('pane-diag').classList.contains('active')) renderDiag(A6.diag.scan(S.state.project).concat(issues));
  }

  /* ---------- 测试 ---------- */
  function renderTests() {
    const box = $('testList');
    box.innerHTML = '';
    (S.state.project.tests || []).forEach((t, i) => {
      const it = document.createElement('div');
      it.className = 'test-item';
      const head = document.createElement('div');
      head.className = 'ti-head';
      const idx = document.createElement('span'); idx.className = 'b-tag'; idx.textContent = 'T' + (i + 1);
      const name = document.createElement('input'); name.className = 'tname'; name.value = t.name || '';
      name.oninput = () => { t.name = name.value; S.save(); };
      const del = document.createElement('button'); del.className = 'btn ghost xs'; del.textContent = '✕';
      del.onclick = () => { S.snapshot('删除测试'); S.state.project.tests = S.state.project.tests.filter(x => x.id !== t.id); S.save(); renderTests(); A6.editor.updateStats(); };
      head.appendChild(idx); head.appendChild(name); head.appendChild(del);

      const ta = document.createElement('textarea');
      ta.value = t.code || '';
      ta.spellcheck = false;
      ta.oninput = () => { t.code = ta.value; S.save(); };
      ta.onblur = () => S.snapshot('编辑测试');

      const acts = document.createElement('div');
      acts.className = 'ti-actions';
      const runOne = document.createElement('button'); runOne.className = 'btn ghost xs'; runOne.textContent = '运行此用例';
      runOne.onclick = () => runTests([t]);
      acts.appendChild(runOne);
      const hint = document.createElement('span'); hint.className = 'muted';
      hint.textContent = '可用：$setScreen / $state / $run / $log / assert';
      acts.appendChild(hint);

      it.appendChild(head); it.appendChild(ta); it.appendChild(acts);
      box.appendChild(it);
    });
    if (!(S.state.project.tests || []).length) box.appendChild(Object.assign(document.createElement('div'), { className: 'empty-hint', textContent: '还没有用例，点右上「+ 用例」' }));
    A6.editor.updateStats();
  }

  function runTests(only) {
    const tests = only || S.state.project.tests || [];
    showTab('test');
    const out = $('testResult');
    out.innerHTML = '';
    if (!tests.length) { logLine(out, 'warn', '没有测试用例'); return; }
    const project = Object.assign({}, S.state.project, { tests });
    const results = A6.runtime.runTests(project, {});
    let pass = 0;
    results.forEach(r => {
      if (r.ok) { pass++; logLine(out, 'ok', '✔ ' + r.name); }
      else logLine(out, 'error', '✘ ' + r.name + '：' + (r.error || '未通过'));
      (r.logs || []).slice(-6).forEach(l => logLine(out, 'debug', '   ' + l.msg));
    });
    logLine(out, pass === results.length ? 'ok' : 'error', '结果：' + pass + ' / ' + results.length + ' 通过');
    toast('测试：' + pass + ' / ' + results.length + ' 通过');
  }

  function addTest() {
    S.snapshot('新增测试');
    const n = (S.state.project.tests || []).length + 1;
    S.state.project.tests.push({
      id: 't' + Date.now(), name: '用例 ' + n,
      code: "// 断言示例：\n$setScreen('jd.coupon');\nassert(text('天天领豆').exists(), '应存在签到按钮');"
    });
    S.save(); renderTests();
  }

  /* ---------- 诊断 ---------- */
  function renderDiag(issues) {
    const box = $('diagList');
    box.innerHTML = '';
    if (!issues.length) {
      box.appendChild(Object.assign(document.createElement('div'), { className: 'diag-empty', textContent: '没有发现问题。改完积木记得跑一遍「测试」和「运行」。' }));
      return;
    }
    issues.forEach(i => {
      const d = document.createElement('div');
      d.className = 'diag ' + i.level;
      const lv = i.level === 'error' ? '错误' : i.level === 'warn' ? '警告' : '提示';
      d.innerHTML = '<span class="lv">' + lv + '</span><div class="d-body"><div class="d-title">' + esc(i.title) + '</div>'
        + (i.hint ? '<div class="d-hint">' + esc(i.hint) + '</div>' : '')
        + (i.loc ? '<div class="d-loc">' + esc(i.loc) + '</div>' : '') + '</div>';
      if (i.blockId) d.onclick = () => { showTab('diag'); A6.editor.scrollToBlock(i.blockId); };
      box.appendChild(d);
    });
  }

  function diagnose() {
    showTab('diag');
    const issues = A6.diag.scan(S.state.project).concat(lastRun ? A6.diag.fromRun(lastRun) : []);
    renderDiag(issues);
    const err = issues.filter(i => i.level === 'error').length;
    const warn = issues.filter(i => i.level === 'warn').length;
    setBadge('diagSummary', err ? 'err' : (warn ? 'warn' : 'ok'), err ? err + ' 错误 · ' + warn + ' 警告' : (warn ? warn + ' 警告' : '无问题'));
  }

  /* ---------- 真机 ---------- */
  async function connectDevice() {
    $('deviceLog').innerHTML = '';
    $('deviceInfo').innerHTML = '';
    applyBridgeCfg();
    try {
      const p = await A6.bridge.ping();
      logLine($('deviceLog'), 'ok', 'MCP 握手成功');
      const info = await A6.bridge.deviceInfo();
      renderDeviceInfo(info);
      $('deviceChip').className = 'chip ok';
      $('deviceChip').textContent = '真机：' + ((info.manufacturer || '') + ' ' + (info.model || '')).trim();
      saveDevice((S.state.project.bridge || {}).url || $('bridgeUrl').value.trim(), ((info.manufacturer || '') + ' ' + (info.model || '')).trim());
    } catch (e) {
      logLine($('deviceLog'), 'error', '连接失败：' + e.message);
      $('deviceChip').className = 'chip err';
      $('deviceChip').textContent = '真机：连接失败';
    }
  }

  function renderDeviceInfo(info) {
    const box = $('deviceInfo');
    const rows = [
      ['厂商 / 型号', (info.manufacturer || '-') + ' / ' + (info.model || '-')],
      ['Android', (info.androidRelease || '-') + ' (API ' + (info.androidApi || '-') + ')'],
      ['AutoJs6', (info.versionName || '-') + ' (' + (info.versionCode || '-') + ')'],
      ['MCP 插件', (info.plugin || '-') + ' / ' + (info.pluginId || '-')],
      ['进程', info.process || '-'],
      ['设备时间', info.time || (info.timestamp ? new Date(info.timestamp).toLocaleString() : '-')]
    ];
    box.innerHTML = rows.map(r => '<div class="r"><span class="k">' + esc(r[0]) + '</span><span class="v">' + esc(r[1]) + '</span></div>').join('');
  }

  async function pingDevice() {
    try {
      applyBridgeCfg();
      const p = await A6.bridge.ping();
      logLine($('deviceLog'), 'ok', 'Ping 正常：' + JSON.stringify(p.data || p.info).slice(0, 300));
    } catch (e) { logLine($('deviceLog'), 'error', 'Ping 失败：' + e.message); }
  }

  async function dumpDevice() {
    try {
      applyBridgeCfg();
      const t = await A6.bridge.uiDumpText({ maxNodes: 120, maxDepth: 20 });
      $('deviceLog').innerHTML = '';
      t.split('\n').slice(0, 80).forEach(l => logLine($('deviceLog'), 'debug', l));
    } catch (e) { logLine($('deviceLog'), 'error', '取界面树失败：' + e.message); }
  }

  /* ---------- 导入导出 ---------- */
  function download(name, text, mime) {
    const blob = new Blob([text], { type: mime || 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a); a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 500);
  }

  function exportJs() {
    const code = A6.gen.code(S.state.project);
    download((S.state.project.name || 'autojs6') + '.js', code, 'text/javascript;charset=utf-8');
    toast('已导出 JS');
  }

  function exportProject() {
    download((S.state.project.name || 'autojs6') + '.a6proj.json', JSON.stringify(S.state.project, null, 2), 'application/json');
    toast('已导出项目');
  }

  function importProject(file) {
    const fr = new FileReader();
    fr.onload = () => {
      try {
        const p = JSON.parse(fr.result);
        if (!p || !p.root) throw new Error('不是有效项目文件');
        p.modules = p.modules || []; p.tests = p.tests || [];
        S.snapshot('导入项目');
        S.state.project = p;
        S.state.scope = { kind: 'root' }; S.state.selected = null;
        S.save(); renderAll(); refreshBridgeField();
        toast('已导入：' + (p.name || '项目'));
      } catch (e) { toast('导入失败：' + e.message); }
    };
    fr.readAsText(file);
  }

  const TOKEN_STORE = 'a6studio.tokens.v1';
  const REMEMBER_STORE = 'a6studio.remember.v1';

  function rememberOn() {
    const cb = $('rememberToken');
    return !!(cb && cb.checked);
  }

  function loadRemembered() {
    if (!rememberOn()) return;
    try {
      const map = JSON.parse(localStorage.getItem(TOKEN_STORE) || '{}');
      A6.bridge.setAuthMap(map);
    } catch (e) { }
  }

  function persistTokens() {
    try {
      if (rememberOn()) localStorage.setItem(TOKEN_STORE, JSON.stringify(A6.bridge.authMap()));
      else localStorage.removeItem(TOKEN_STORE);
    } catch (e) { }
  }

  function fillTokenFor(url) {
    const tk = $('bridgeToken');
    if (!tk) return;
    const u = String(url || (S.state.project.bridge && S.state.project.bridge.url) || '').trim();
    const v = A6.bridge.authMap()[u];
    tk.value = v ? String(v).replace(/^Bearer\s/i, '') : '';
  }

  function applyBridgeCfg() {
    const url = $('bridgeUrl').value.trim();
    A6.bridge.setUrl(url);
    const tk = $('bridgeToken');
    const token = tk ? tk.value.trim() : '';
    if (token) A6.bridge.setAuthFor(url, token);
    persistTokens();
  }

  /* 多设备：地址持久化到项目，令牌按地址记在内存 */
  function ensureDevices() {
    if (!S.state.project.bridge) S.state.project.bridge = {};
    const b = S.state.project.bridge;
    if (!Array.isArray(b.devices)) b.devices = [];
    /* 空项目/老项目：用本机配置里的常用设备兜底，保证打开就能选 */
    if (!b.devices.length && A6.config && Array.isArray(A6.config.devices) && A6.config.devices.length) {
      b.devices = JSON.parse(JSON.stringify(A6.config.devices));
      if (!b.url) b.url = A6.config.defaultUrl || '';
    }
    /* 名称是「裸地址」时，用本机配置里的友好名（魅族/一加）补上 */
    const cfgList = (A6.config && Array.isArray(A6.config.devices)) ? A6.config.devices : [];
    b.devices.forEach(d => {
      const bare = String(d.url || '').replace(/^https?:\/\//, '');
      if (!d.name || d.name === bare || d.name.indexOf('://') >= 0) {
        const hit = cfgList.find(c => c.url === d.url);
        if (hit && hit.name) d.name = hit.name;
        else if (!d.name) d.name = bare;
      }
    });
    return b.devices;
  }

  function renderDeviceList() {
    const sel = $('bridgeDevice');
    if (!sel) return;
    const list = ensureDevices();
    const cur = ($('bridgeUrl') || {}).value || (S.state.project.bridge || {}).url || '';
    sel.innerHTML = '<option value="">— 已保存设备（' + list.length + '）—</option>'
      + list.map(d => '<option value="' + esc(d.url) + '"' + (d.url === cur ? ' selected' : '') + '>'
        + esc(d.name || d.url) + ' · ' + esc(d.url.replace(/^https?:\/\//, '')) + '</option>').join('');
  }

  function saveDevice(url, name) {
    url = String(url || '').trim();
    if (!url) return false;
    const list = ensureDevices();
    const hit = list.find(d => d.url === url);
    if (hit) { if (name) hit.name = name; else if (!hit.name) hit.name = url.replace(/^https?:\/\//, ''); }
    else list.push({ url: url, name: name || url.replace(/^https?:\/\//, '') });
    S.save();
    renderDeviceList();
    return true;
  }

  function refreshBridgeField() {
    const url = (S.state.project.bridge && S.state.project.bridge.url) || 'http://127.0.0.1:8765/mcp';
    $('bridgeUrl').value = url;
    renderDeviceList();
    fillTokenFor(url);
  }

  /* ---------- Tab ---------- */
  function showTab(name) {
    document.querySelectorAll('#rightTabs .tab').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
    document.querySelectorAll('.tabpane').forEach(p => p.classList.toggle('active', p.id === 'pane-' + name));
    if (name === 'diag') renderDiag(A6.diag.scan(S.state.project).concat(lastRun ? A6.diag.fromRun(lastRun) : []));
  }

  /* ---------- 绑定 ---------- */
  function bind() {
    document.querySelectorAll('#rightTabs .tab').forEach(b => b.onclick = () => showTab(b.dataset.tab));
    $('btnRun').onclick = run;
    $('btnTest').onclick = () => runTests();
    $('btnDiag').onclick = diagnose;
    $('btnSave').onclick = () => { S.save(); toast('已保存到本机'); };
    $('btnUndo').onclick = () => { const l = S.undo(); if (l) { renderAll(); toast('撤销：' + l); } else toast('没有可撤销的操作'); };
    $('btnExportJs').onclick = exportJs;
    $('btnExportProject').onclick = exportProject;
    $('btnImport').onclick = () => $('fileInput').click();
    $('fileInput').onchange = e => { if (e.target.files[0]) importProject(e.target.files[0]); e.target.value = ''; };
    $('btnExample').onclick = () => {
      if (!confirm('用内置示例覆盖当前项目？')) return;
      S.snapshot('载入示例');
      S.state.project = S.exampleProject();
      S.state.scope = { kind: 'root' }; S.state.selected = null;
      S.save(); renderAll(); refreshBridgeField(); toast('已载入示例');
    };
    $('btnNewModule').onclick = () => {
      const name = prompt('模块名（生成函数用，建议英文）', '模块' + ((S.state.project.modules || []).length + 1));
      if (!name) return;
      S.snapshot('新建模块');
      S.state.project.modules.push({ id: 'mod_' + Date.now(), name, args: '', desc: '', children: { body: [] } });
      S.state.scope = { kind: 'module', id: S.state.project.modules.slice(-1)[0].id };
      S.save(); renderAll();
    };
    $('btnAddTest').onclick = addTest;
    $('btnRunTests').onclick = () => runTests();
    $('btnClearLog').onclick = () => { $('runLog').innerHTML = ''; };
    $('btnCopyCode').onclick = async () => {
      try { await navigator.clipboard.writeText(A6.gen.code(S.state.project)); toast('代码已复制'); }
      catch (e) { toast('复制失败，请手动选择'); }
    };
    $('projectName').oninput = e => { S.state.project.name = e.target.value; S.save(); };
    $('paletteSearch').oninput = e => A6.editor.renderPalette(e.target.value);
    $('btnBridgeConnect').onclick = connectDevice;
    const rcb = $('rememberToken');
    if (rcb) {
      rcb.checked = localStorage.getItem(REMEMBER_STORE) === '1';
      rcb.onchange = () => {
        localStorage.setItem(REMEMBER_STORE, rcb.checked ? '1' : '0');
        if (rcb.checked) { loadRemembered(); fillTokenFor($('bridgeUrl').value.trim()); }
        persistTokens();
        fillTokenFor($('bridgeUrl').value.trim());
      };
      if (rcb.checked) loadRemembered();
    }
    const devSel = $('bridgeDevice');
    if (devSel) devSel.onchange = e => {
      const url = e.target.value;
      if (!url) return;
      $('bridgeUrl').value = url;
      if (!S.state.project.bridge) S.state.project.bridge = {};
      S.state.project.bridge.url = url;
      S.save();
      applyBridgeCfg();
      fillTokenFor(url);
      toast('已切换到 ' + url.replace(/^https?:\/\//, ''));
    };
    const saveBtn = $('btnSaveDevice');
    if (saveBtn) saveBtn.onclick = () => {
      if (saveDevice($('bridgeUrl').value.trim())) toast('已保存设备');
    };
    $('btnBridgePing').onclick = pingDevice;
    $('btnBridgeDump').onclick = dumpDevice;
    $('deviceChip').onclick = () => { showTab('device'); $('bridgeUrl').focus(); };
    $('bridgeUrl').onchange = e => {
      if (!S.state.project.bridge) S.state.project.bridge = {};
      S.state.project.bridge.url = e.target.value.trim(); S.save();
    };

    document.addEventListener('keydown', e => {
      const typing = /INPUT|TEXTAREA|SELECT/.test((e.target.tagName || ''));
      if (e.key === 'F5') { e.preventDefault(); run(); }
      else if (e.key === 'F6') { e.preventDefault(); runTests(); }
      else if (e.key === 'F7') { e.preventDefault(); diagnose(); }
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); S.save(); toast('已保存'); }
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'e') { e.preventDefault(); exportJs(); }
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); $('paletteSearch').focus(); }
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !typing) { e.preventDefault(); const l = S.undo(); if (l) { renderAll(); toast('撤销：' + l); } }
      else if (e.key === 'Delete' && !typing && S.state.selected) { A6.editor.remove(S.state.selected); }
      else if (e.key === 'Escape') {
        if (A6.picker && A6.picker.isOpen()) { A6.picker.close(); return; }
        S.state.selected = null; document.querySelectorAll('.block.selected').forEach(n => n.classList.remove('selected')); setHint(''); highlightCode(null);
      }
    });
    window.addEventListener('beforeunload', () => S.save());
  }

  A6.ui = { toast, refreshCode, highlightCode, scheduleDiag, commit, renderAll, setHint, showTab, renderDiag, diagnose, runTests, run, deviceLog: (l, m) => logLine($('deviceLog'), l, m) };

  function init() {
    S.load();
    bind();
    refreshBridgeField();
    renderAll();
    showTab('code');
    setBadge('runStatus', 'idle', '未运行');
    setTimeout(scheduleDiag, 60);
    if (!localStorage.getItem('a6studio.seen')) {
      localStorage.setItem('a6studio.seen', '1');
      setTimeout(() => toast('已载入京东领券中心示例，按 F5 运行'), 400);
    }
  }

  document.addEventListener('DOMContentLoaded', init);
})();