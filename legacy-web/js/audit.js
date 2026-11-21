/* AutoJs6 Studio · 选择器体检（P3）
 * 扫一遍项目里所有 by/value 选择器，连真机（或 Mock）逐个 ui_find 校验：
 * 唯一 / 命中多个 / 未找到（附 ui_explain_selector 的首个失败条件），
 * 命中多个时从命中节点反推候选，找到唯一候选就提供一键修好。
 */
(function () {
  const S = A6.store;
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));

  const BY_SET = { id: 1, idMatches: 1, text: 1, textContains: 1, desc: 1, descContains: 1 };
  const FN = { id: 'id', idMatches: 'idMatches', text: 'text', textContains: 'textContains', desc: 'desc', descContains: 'descContains' };

  let rows = [];        // 体检结果
  let busy = false;
  let bound = false;

  function selText(by, value) { return (FN[by] || 'text') + '(' + JSON.stringify(String(value)) + ')'; }
  function blockLabel(b) {
    const d = A6.catalog.byType(b.type);
    const base = d ? d.name.replace(/\{[^}]*\}/g, '').replace(/\s+/g, ' ').trim() : b.type;
    return base + ' · ' + selText(b.params.by, b.params.value);
  }

  /* 收集所有用 by/value 的选择器积木（表达式积木单独标注） */
  function collect() {
    const out = [];
    const add = (b, scope) => {
      const p = b.params || {};
      if (!BY_SET[p.by] || !String(p.value == null ? '' : p.value).trim()) return;
      const hasExpr = p.expr != null && String(p.expr).trim();
      out.push({
        blockId: b.id,
        scope: scope || '主程序',
        label: blockLabel(b),
        name: (A6.catalog.byType(b.type) || {}).name || b.type,
        by: p.by,
        value: String(p.value),
        expr: hasExpr ? String(p.expr) : '',
        count: null,
        status: hasExpr ? 'expr' : 'idle',
        note: hasExpr ? '已填表达式，代码走 expr，by/value 不再使用' : '',
        fix: null,
        score: null
      });
    };
    const walk = (list, scope) => {
      (list || []).forEach(b => {
        add(b, scope);
        Object.keys(b.children || {}).forEach(k => walk(b.children[k], scope));
      });
    };
    walk(S.state.project.root.children.body, '主程序');
    (S.state.project.modules || []).forEach(m => walk(m.children.body, '模块：' + m.name));
    return out;
  }

  /* ---------- 界面 ---------- */
  function ensureDom() {
    if ($('audit')) return;
    const wrap = document.createElement('div');
    wrap.id = 'audit';
    wrap.className = 'audit-mask';
    wrap.innerHTML = ''
      + '<div class="audit-panel">'
      + '  <div class="audit-head">'
      + '    <b>选择器体检</b><span id="auditMeta" class="audit-meta">—</span>'
      + '    <span class="audit-spacer"></span>'
      + '    <button id="auditRun" class="btn xs">体检全部</button>'
      + '    <button id="auditFix" class="btn primary xs" disabled>修好可修的</button>'
      + '    <button id="auditCopy" class="btn ghost xs">复制报告</button>'
      + '    <button id="auditClose" class="btn ghost xs">✕</button>'
      + '  </div>'
      + '  <div id="auditList" class="audit-list scroll"></div>'
      + '  <div id="auditFoot" class="audit-foot">体检只读页面、只调 ui_find / ui_explain_selector，不改脚本；修好会先快照，可 Ctrl+Z 撤销。</div>'
      + '</div>';
    document.body.appendChild(wrap);
    $('auditClose').onclick = close;
    $('auditRun').onclick = () => run();
    $('auditFix').onclick = () => applyAll();
    $('auditCopy').onclick = () => copyReport();
    wrap.addEventListener('click', ev => { if (ev.target === wrap) close(); });
    bound = true;
  }

  function chipOf(r) {
    if (r.status === 'unique') return '<span class="ar-chip ok">唯一</span>';
    if (r.status === 'multiple') return '<span class="ar-chip warn">' + r.count + ' 个</span>';
    if (r.status === 'missing') return '<span class="ar-chip bad">未找到</span>';
    if (r.status === 'error') return '<span class="ar-chip bad">出错</span>';
    if (r.status === 'expr') return '<span class="ar-chip skip">表达式</span>';
    return '<span class="ar-chip idle">待检</span>';
  }

  function renderList() {
    const box = $('auditList');
    if (!box) return;
    if (!rows.length) {
      box.innerHTML = '<div class="audit-empty">项目里没有 by/value 选择器积木（只有表达式或坐标类积木时没有可体检项）。</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    rows.forEach((r, i) => {
      const row = document.createElement('div');
      row.className = 'audit-row' + (r.fix ? ' fixable' : '');
      row.innerHTML = ''
        + '<div class="ar-top">' + chipOf(r)
        + '<span class="ar-name">' + esc(r.name) + '</span>'
        + '<span class="ar-scope">' + esc(r.scope) + '</span>'
        + (r.score != null ? '<span class="ar-score">评分 ' + r.score + '</span>' : '')
        + '</div>'
        + '<div class="ar-sel">' + esc(selText(r.by, r.value)) + (r.expr ? '<span class="ar-fx">fx</span>' : '') + '</div>'
        + (r.note ? '<div class="ar-note">' + esc(r.note) + '</div>' : '')
        + (r.fix ? '<div class="ar-fix">可改为 ' + esc(selText(r.fix.by, r.fix.value)) + '</div>' : '');
      const act = document.createElement('button');
      act.className = 'btn ghost xs ar-pick';
      act.textContent = '拾取器';
      act.onclick = () => openPicker(r.blockId);
      row.appendChild(act);
      frag.appendChild(row);
    });
    box.innerHTML = '';
    box.appendChild(frag);
  }

  function renderMeta() {
    const n = rows.length;
    const ok = rows.filter(r => r.status === 'unique').length;
    const bad = rows.filter(r => r.status === 'multiple' || r.status === 'missing' || r.status === 'error').length;
    const fixable = rows.filter(r => r.fix).length;
    if ($('auditMeta')) $('auditMeta').textContent = n ? (n + ' 个选择器 · 唯一 ' + ok + ' · 问题 ' + bad + ' · 可修 ' + fixable) : '—';
    const fx = $('auditFix');
    if (fx) {
      fx.disabled = !fixable || busy;
      fx.textContent = fixable ? ('修好 ' + fixable + ' 处') : '修好可修的';
    }
  }

  /* ---------- 体检 ---------- */
  async function auditOne(r) {
    if (r.expr) { r.status = 'expr'; return; }
    try {
      const f = await A6.bridge.uiFind({ [r.by]: r.value }, 20);
      r.count = f.count;
      if (f.count === 0) {
        r.status = 'missing';
        r.note = '这条选择器在当前页面找不到节点';
        try {
          const ex = await A6.bridge.explainSelector({ [r.by]: r.value });
          const fail = (ex && (ex.firstFail || ex.firstFailingCondition || ex.failedCondition)) || null;
          if (fail) r.note += '；卡在「' + fail + '」';
          else if (ex && ex.steps && ex.steps.length) {
            const s = ex.steps.find(x => x.passed === 0 || x.count === 0);
            if (s) r.note += '；卡在「' + (s.condition || s.name || '') + '」';
          }
        } catch (e) { r.note += '（explain 不可用：' + (e.message || e) + '）'; }
        return;
      }
      if (f.count === 1) {
        r.status = 'unique';
        const node = f.nodes && f.nodes[0] ? A6.picker.pure.normalize(f.nodes[0]) : { clickable: true };
        r.score = A6.picker.pure.scoreOf({ by: r.by }, node, 1);
        r.note = '';
        return;
      }
      r.status = 'multiple';
      r.note = '命中 ' + f.count + ' 个，点击会落到第一个';
      const first = f.nodes && f.nodes[0];
      if (!first) return;
      const cand = A6.picker.pure.buildCandidates(A6.picker.pure.normalize(first))
        .filter(c => !(c.by === r.by && c.value === r.value));
      for (const c of cand) {
        try {
          const cf = await A6.bridge.uiFind(c.sel, 20);
          if (cf.count === 1) {
            r.fix = { by: c.by, value: c.value };
            r.note += '；可用 ' + selText(c.by, c.value) + ' 收窄到唯一';
            break;
          }
        } catch (e) { /* 单条候选失败不影响整体 */ }
      }
    } catch (e) {
      r.status = 'error';
      r.note = '体检失败：' + (e.code ? e.code + ' ' : '') + (e.message || e);
    }
  }

  async function run() {
    if (busy) return;
    busy = true;
    rows = collect();
    renderList(); renderMeta();
    if (!rows.length) { busy = false; return; }
    $('auditFoot').textContent = '正在逐个校验…';
    for (let i = 0; i < rows.length; i++) {
      await auditOne(rows[i]);
      renderList(); renderMeta();
      $('auditFoot').textContent = '体检中 ' + (i + 1) + '/' + rows.length + '…';
    }
    busy = false;
    renderList(); renderMeta();
    const bad = rows.filter(r => r.status === 'multiple' || r.status === 'missing' || r.status === 'error').length;
    $('auditFoot').textContent = bad
      ? bad + ' 个选择器有问题：能自动收窄的已给建议，其余用「拾取器」重拾；修好会先快照。'
      : '全部唯一，没有需要改的选择器。';
    A6.ui.toast('体检完成：' + rows.length + ' 个选择器，' + bad + ' 个有问题');
  }

  function applyAll() {
    const fixable = rows.filter(r => r.fix);
    if (!fixable.length) return;
    S.snapshot('体检修好选择器');
    let n = 0;
    fixable.forEach(r => {
      const found = S.blockById(r.blockId);
      if (!found) return;
      found.block.params.by = r.fix.by;
      found.block.params.value = r.fix.value;
      r.status = 'unique';
      r.note = '已改为 ' + selText(r.fix.by, r.fix.value);
      r.by = r.fix.by; r.value = r.fix.value;
      r.fix = null;
      n++;
    });
    if (!n) { A6.ui.toast('目标积木已被删除，没有改动'); return; }
    S.save();
    A6.ui.commit('体检修好选择器');
    renderList(); renderMeta();
    $('auditFoot').textContent = '已修好 ' + n + ' 处，可 Ctrl+Z 撤销。';
    A6.ui.toast('已修好 ' + n + ' 处选择器');
  }

  function report() {
    const head = '| 状态 | 积木 | 范围 | 选择器 | 说明 |\n| --- | --- | --- | --- | --- |';
    const lines = rows.map(r => {
      const st = r.status === 'unique' ? '唯一' : r.status === 'multiple' ? (r.count + ' 个') : r.status === 'missing' ? '未找到' : r.status === 'expr' ? '表达式' : r.status === 'error' ? '出错' : '待检';
      return '| ' + st + ' | ' + r.name + ' | ' + r.scope + ' | `' + selText(r.by, r.value) + '` | ' + (r.note || '') + ' |';
    });
    return '选择器体检报告\n\n' + [head].concat(lines).join('\n') + '\n';
  }

  function copyReport() {
    if (!rows.length) { A6.ui.toast('先体检一次'); return; }
    const txt = report();
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(txt).then(() => A6.ui.toast('报告已复制'), () => A6.ui.toast('复制失败，请手动选择文本'));
    } else {
      A6.ui.toast('当前环境不支持自动复制');
    }
  }

  function openPicker(blockId) {
    const found = S.blockById(blockId);
    if (!found) { A6.ui.toast('目标积木已被删除'); return; }
    close();
    A6.picker.open({ blockId, paramKey: 'value', kind: 'selector' });
  }

  function open() {
    ensureDom();
    const el = $('audit');
    el.classList.add('open');
    if (!rows.length) { rows = collect(); }
    renderList(); renderMeta();
    if (!bound) bound = true;
    if (!rows.length) $('auditFoot').textContent = '项目里没有 by/value 选择器积木：表达式与坐标类积木不参与体检。';
  }

  function close() {
    const el = $('audit');
    if (el) el.classList.remove('open');
  }

  function isOpen() {
    const el = $('audit');
    return !!(el && el.classList.contains('open'));
  }

  /* 工具条按钮：用 JS 注入，不依赖 html 改版 */
  function bindToolbar() {
    const bar = document.querySelector('.topbar .actions') || document.querySelector('.topbar') || document.querySelector('.canvas-toolbar');
    if (!bar || $('btnAudit')) return;
    const b = document.createElement('button');
    b.id = 'btnAudit';
    b.className = 'btn';
    b.title = '检查项目里所有选择器（F8）';
    b.textContent = '⌕ 体检';
    b.onclick = () => open();
    const diag = $('btnDiag');
    if (diag && diag.parentNode === bar) bar.insertBefore(b, diag.nextSibling);
    else bar.appendChild(b);
  }

  document.addEventListener('keydown', ev => {
    if (ev.key === 'F8') { ev.preventDefault(); open(); }
    else if (ev.key === 'Escape' && isOpen()) { close(); }
  });

  function init() {
    bindToolbar();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  A6.audit = { open, close, isOpen, run, applyAll, copyReport, report, collect, results: () => rows };
})();