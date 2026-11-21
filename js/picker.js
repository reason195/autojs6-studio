/* AutoJs6 Studio · 控件拾取器
 * P0：界面树点选 + 选择器合成 + 唯一性校验 + 回填
 * P1：截图叠层点选（按 bounds 百分比叠热区）+ 点空白取坐标
 * 只依赖 MCP 的 ui_dump(json) / ui_find / ui_explain_selector / ui_current_window / screen_capture。
 */
(function () {
  const S = A6.store;
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));

  let target = null;          // {blockId, paramKey, kind:'selector'|'coord'}
  let nodes = [];
  let meta = {};
  let device = { w: 1080, h: 2376 };
  let selected = null;
  let cands = [];
  let chosen = null;
  let coord = null;           // {x, y} 截图取点
  let query = '';
  let onlyClick = false;
  let bound = false;
  let busy = false;
  let view = 'list';          // list | shot
  let shot = null;            // {url, seq}
  let shotBusy = false;
  let dumpSeq = 0;
  let region = null;          // {left,top,right,bottom} 下钻区域（设备像素）
  let fullNodes = null;       // 下钻前的全量节点，用于清除区域
  let drillBusy = false;
  let pts = null;             // swipe 起止点 {a:{x,y}, b:{x,y}}

  /* ---------- 小工具 ---------- */
  const shortClass = c => String(c || '').replace(/^android\.(widget|view|webkit)\./, '');
  const shortId = id => String(id || '').split('/').pop();
  const escapeRe = s => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const q = s => JSON.stringify(String(s == null ? '' : s));
  function setMeta(txt) { const el = $('pickMeta'); if (el) el.textContent = txt || ''; }

  /* ---------- 数据 ---------- */
  function normalize(n) {
    const b = n.bounds || {};
    const c = n.center || [Math.round(((b.left || 0) + (b.right || 0)) / 2), Math.round(((b.top || 0) + (b.bottom || 0)) / 2)];
    return {
      depth: n.depth || 0,
      className: n.className || '',
      text: n.text || '',
      desc: n.desc || '',
      id: n.id || '',
      bounds: { left: b.left || 0, top: b.top || 0, right: b.right || 0, bottom: b.bottom || 0 },
      center: c,
      clickable: !!n.clickable,
      editable: !!n.editable,
      scrollable: !!n.scrollable,
      enabled: n.enabled !== false,
      visible: n.visible !== false,
      childCount: n.childCount || 0
    };
  }

  function computeDevice() {
    let w = 0, h = 0;
    nodes.forEach(n => { w = Math.max(w, n.bounds.right); h = Math.max(h, n.bounds.bottom); });
    device = { w: w || 1080, h: h || 2376 };
  }

  /* 单条件候选：验证用的选择器与写进积木的 by/value 必须完全一致 */
  function buildCandidates(n) {
    const out = [];
    if (n.id) {
      out.push({ by: 'id', value: n.id, sel: { id: n.id }, label: 'id' });
      out.push({ by: 'idMatches', value: '.*' + escapeRe(shortId(n.id)) + '$', sel: { idMatches: '.*' + escapeRe(shortId(n.id)) + '$' }, label: 'id 正则' });
    }
    if (n.text) out.push({ by: 'text', value: n.text, sel: { text: n.text }, label: '文本' });
    if (n.desc) out.push({ by: 'desc', value: n.desc, sel: { desc: n.desc }, label: '描述' });
    if (n.text) out.push({ by: 'textContains', value: n.text, sel: { textContains: n.text }, label: '文本包含' });
    if (n.desc) out.push({ by: 'descContains', value: n.desc, sel: { descContains: n.desc }, label: '描述包含' });
    return out;
  }

  function scoreOf(c, n, count) {
    let s = 100;
    if (c.by === 'idMatches') s -= 8;
    else if (c.by === 'text') s -= 10;
    else if (c.by === 'desc') s -= 6;
    else if (c.by === 'textContains') s -= 16;
    else if (c.by === 'descContains') s -= 14;
    if (count > 1) s -= 8;
    if (count === 0) s -= 25;
    if (!n.clickable) s -= 5;
    return Math.max(0, s);
  }

  function toExpr(c) {
    const fn = { id: 'id', idMatches: 'idMatches', text: 'text', desc: 'desc', textContains: 'textContains', descContains: 'descContains' }[c.by] || 'text';
    return fn + '(' + q(c.value) + ')';
  }

  function matches(n) {
    if (onlyClick && !n.clickable) return false;
    const kw = query.trim().toLowerCase();
    if (!kw) return true;
    return (n.text + ' ' + n.desc + ' ' + n.id + ' ' + n.className).toLowerCase().indexOf(kw) >= 0;
  }

  /* ---------- 列表视图 ---------- */
  function renderTree() {
    const box = $('pickTree');
    if (!box) return;
    box.innerHTML = '';
    const rows = nodes.filter(matches);
    if (!rows.length) { box.innerHTML = '<div class="pick-empty">没有匹配的节点</div>'; return; }
    const frag = document.createDocumentFragment();
    rows.forEach(n => {
      const row = document.createElement('div');
      row.className = 'pick-row' + (n === selected ? ' sel' : '');
      row.style.paddingLeft = (6 + n.depth * 12) + 'px';
      row.innerHTML = '<span class="pr-cls">' + esc(shortClass(n.className)) + '</span>'
        + '<span class="pr-txt">' + esc(n.text || n.desc || '—') + '</span>'
        + (n.id ? '<span class="pr-id">' + esc(shortId(n.id)) + '</span>' : '')
        + (n.clickable ? '<span class="pr-chip">可点</span>' : '')
        + (n.editable ? '<span class="pr-chip">输入</span>' : '');
      row.onclick = () => pickNode(n);
      frag.appendChild(row);
    });
    box.appendChild(frag);
  }

  /* ---------- 截图视图 ---------- */
  function renderShot() {
    const hot = $('shotHot');
    if (!hot) return;
    const box = $('shotBox');
    if (box) box.style.setProperty('--ar', device.w + '/' + device.h);
    hot.innerHTML = '';
    const area = device.w * device.h;
    nodes.filter(matches).forEach(n => {
      const b = n.bounds, w = b.right - b.left, h = b.bottom - b.top;
      if (w <= 0 || h <= 0) return;
      /* 全屏容器会盖住所有控件，没文案就不画 */
      if (!n.text && !n.desc && (w * h) / area > 0.85) return;
      const d = document.createElement('div');
      d.className = 'shbox' + (n.clickable ? ' click' : '') + (n === selected ? ' sel' : '');
      d.style.left = (b.left / device.w * 100) + '%';
      d.style.top = (b.top / device.h * 100) + '%';
      d.style.width = (w / device.w * 100) + '%';
      d.style.height = (h / device.h * 100) + '%';
      d.title = [n.text || n.desc || shortClass(n.className), n.id ? 'id=' + shortId(n.id) : ''].filter(Boolean).join(' · ');
      d.addEventListener('click', ev => { ev.stopPropagation(); pickNode(n); });
      hot.appendChild(d);
    });
    if (pts) {
      if (pts.a && pts.b) {
        const svgNS = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(svgNS, 'svg');
        svg.setAttribute('class', 'shot-line');
        svg.setAttribute('viewBox', '0 0 ' + device.w + ' ' + device.h);
        svg.setAttribute('preserveAspectRatio', 'none');
        const ln = document.createElementNS(svgNS, 'line');
        ln.setAttribute('x1', pts.a.x); ln.setAttribute('y1', pts.a.y);
        ln.setAttribute('x2', pts.b.x); ln.setAttribute('y2', pts.b.y);
        ln.setAttribute('vector-effect', 'non-scaling-stroke');
        svg.appendChild(ln);
        hot.appendChild(svg);
      }
      const mark = (p, label) => {
        if (!p) return;
        const d = document.createElement('div');
        d.className = 'pt-mark';
        d.style.left = (p.x / device.w * 100) + '%';
        d.style.top = (p.y / device.h * 100) + '%';
        d.innerHTML = '<span>' + label + ' ' + p.x + ', ' + p.y + '</span>';
        hot.appendChild(d);
      };
      mark(pts.a, '①'); mark(pts.b, '②');
    }
    if (region) {
      const rg = document.createElement('div');
      rg.className = 'region-rect';
      rg.style.left = (region.left / device.w * 100) + '%';
      rg.style.top = (region.top / device.h * 100) + '%';
      rg.style.width = ((region.right - region.left) / device.w * 100) + '%';
      rg.style.height = ((region.bottom - region.top) / device.h * 100) + '%';
      hot.appendChild(rg);
    }
    if (coord) {
      const m = document.createElement('div');
      m.className = 'coord-mark';
      m.style.left = (coord.x / device.w * 100) + '%';
      m.style.top = (coord.y / device.h * 100) + '%';
      m.innerHTML = '<span>' + coord.x + ', ' + coord.y + '</span>';
      hot.appendChild(m);
    }
  }

  async function ensureShot() {
    if (shotBusy || !nodes.length) return;
    if (shot && shot.seq === dumpSeq) { renderShot(); return; }
    shotBusy = true;
    const hint = $('shotHint');
    if (hint) hint.textContent = '正在截图…';
    try {
      const s = await A6.bridge.screenCapture({ format: 'jpeg', quality: 70, maxWidth: 1080 });
      shot = { url: s.url, seq: dumpSeq };
      const img = $('shotImg');
      if (img) { img.src = s.url; }
      if (hint) hint.textContent = '点绿框选控件；点空白处取坐标';
      renderShot();
    } catch (e) {
      let msg = '截图失败：' + (e.message || e);
      if (e.code === 'RATE_LIMITED' && e.retryAfterMs) msg += '（约 ' + Math.ceil(e.retryAfterMs / 1000) + ' 秒后可重试）';
      if (hint) hint.innerHTML = esc(msg) + '<button id="shotRetry" class="btn ghost xs" style="margin-left:6px">重试</button>';
      const b = $('shotRetry');
      if (b) b.onclick = () => ensureShot();
    } finally {
      shotBusy = false;
    }
  }

  function toDevice(ev) {
    const hot = $('shotHot');
    if (!hot) return null;
    const r = hot.getBoundingClientRect();
    const left = r.left + hot.clientLeft, top = r.top + hot.clientTop;
    const w = hot.clientWidth || r.width, h = hot.clientHeight || r.height;
    if (!w || !h) return null;
    return {
      x: Math.max(0, Math.min(device.w, Math.round((ev.clientX - left) / w * device.w))),
      y: Math.max(0, Math.min(device.h, Math.round((ev.clientY - top) / h * device.h)))
    };
  }

  /* 同一个手势层：没拖动 = 取点；拖出 >10px = 框选区域下钻 */
  function bindShot() {
    const hot = $('shotHot');
    if (!hot) return;
    let start = null, drawing = false, box = null;

    hot.addEventListener('mousedown', ev => {
      if (ev.target !== hot) return;
      ev.preventDefault();
      start = { x: ev.clientX, y: ev.clientY };
      drawing = false; box = null;
    });

    document.addEventListener('mousemove', ev => {
      if (!start) return;
      if (!drawing && Math.abs(ev.clientX - start.x) + Math.abs(ev.clientY - start.y) > 10) {
        drawing = true;
        box = document.createElement('div');
        box.className = 'region-draw';
        hot.appendChild(box);
      }
      if (!drawing || !box) return;
      const r = hot.getBoundingClientRect();
      box.style.left = (Math.min(start.x, ev.clientX) - r.left) + 'px';
      box.style.top = (Math.min(start.y, ev.clientY) - r.top) + 'px';
      box.style.width = Math.abs(ev.clientX - start.x) + 'px';
      box.style.height = Math.abs(ev.clientY - start.y) + 'px';
    });

    document.addEventListener('mouseup', ev => {
      if (!start) return;
      const s0 = start, wasDrawing = drawing, b = box;
      start = null; drawing = false; box = null;
      if (wasDrawing) {
        if (b) b.remove();
        const a = toDevice({ clientX: Math.min(s0.x, ev.clientX), clientY: Math.min(s0.y, ev.clientY) });
        const z = toDevice({ clientX: Math.max(s0.x, ev.clientX), clientY: Math.max(s0.y, ev.clientY) });
        if (!a || !z) return;
        const rect = { left: a.x, top: a.y, right: z.x, bottom: z.y };
        if (rect.right - rect.left < 12 || rect.bottom - rect.top < 12) { A6.ui.toast('框选太小，已忽略'); return; }
        drill(rect);
        return;
      }
      const pt = toDevice(ev);
      if (!pt) return;
      if (pts) { takePoint(pt); return; }
      coord = pt;
      renderShot(); renderDetail();
      if (target && target.kind === 'coord') A6.ui.toast('已取点 ' + coord.x + ', ' + coord.y + '，点「用这个坐标填入」');
    });
  }

  /* 区域下钻返回的是扁平列表，按 bounds 包含关系重建层级 */
  function buildHierarchy(list) {
    const out = list.slice();
    const area = b => (b.right - b.left) * (b.bottom - b.top);
    out.forEach(n => {
      let best = null;
      for (const m of out) {
        if (m === n) continue;
        const mb = m.bounds, nb = n.bounds;
        const contains = mb.left <= nb.left && mb.top <= nb.top && mb.right >= nb.right && mb.bottom >= nb.bottom;
        if (contains && area(mb) > area(nb) && (!best || area(mb) < area(best.bounds))) best = m;
      }
      n.parent = best;
    });
    out.forEach(n => {
      let d = 0, p = n.parent; const seen = new Set();
      while (p && !seen.has(p)) { seen.add(p); d++; p = p.parent; }
      n.depth = d;
    });
    const roots = out.filter(n => !n.parent).sort((a, b) => a.bounds.top - b.bounds.top || a.bounds.left - b.bounds.left);
    const ordered = [];
    const walk = n => {
      ordered.push(n);
      out.filter(c => c.parent === n).sort((a, b) => a.bounds.top - b.bounds.top || a.bounds.left - b.bounds.left).forEach(walk);
    };
    roots.forEach(walk);
    return ordered;
  }

  /* ui_dump 不支持区域参数，改用 ui_find + boundsInside，再重建层级 */
  async function drill(rect) {
    if (drillBusy) return;
    drillBusy = true;
    const hint = $('shotHint');
    if (hint) hint.textContent = '正在按区域取节点…';
    try {
      const f = await A6.bridge.uiFind({ boundsInside: rect }, 100);
      const list = (f.nodes || []).map(normalize).filter(n => n.bounds.right > n.bounds.left && n.bounds.bottom > n.bounds.top);
      if (!list.length) {
        if (hint) hint.textContent = '该区域没有独立控件：换个范围，或直接点空白取坐标';
        return;
      }
      if (!fullNodes) fullNodes = nodes;
      region = rect;
      nodes = buildHierarchy(list);
      selected = null; cands = []; chosen = null;
      updateScope(); renderTree(); renderShot(); renderDetail();
      const cut = f.truncated || f.count > (f.returned || list.length);
      if (hint) hint.textContent = cut
        ? '区域命中 ' + f.count + ' 个，仅取到前 ' + (f.returned || list.length) + ' 个，建议再缩小范围'
        : '已下钻：' + nodes.length + ' 个节点';
    } catch (e) {
      if (hint) hint.textContent = '区域取节点失败：' + (e.message || e);
    } finally {
      drillBusy = false;
    }
  }

  function updateScope() {
    const bar = $('pickScope');
    if (!bar) return;
    if (!region) { bar.hidden = true; bar.innerHTML = ''; return; }
    bar.hidden = false;
    bar.innerHTML = '<span class="muted">区域</span><b>' + esc([region.left, region.top, region.right, region.bottom].join(', ')) + '</b>'
      + '<span class="muted">' + nodes.length + ' 个节点</span>'
      + '<button id="pickScopeClear" class="btn ghost xs">清除区域</button>';
    const c = $('pickScopeClear');
    if (c) c.onclick = clearRegion;
  }

  function clearRegion() {
    if (!fullNodes) return;
    nodes = fullNodes; fullNodes = null; region = null;
    selected = null; cands = []; chosen = null; coord = null;
    updateScope(); renderTree(); renderShot(); renderDetail();
    const hint = $('shotHint');
    if (hint) hint.textContent = '点绿框选控件；点空白取坐标；拖拽框选区域';
  }

  function setView(v) {
    view = v;
    const lb = $('pickViewList'), sb = $('pickViewShot');
    if (lb) lb.classList.toggle('active', v === 'list');
    if (sb) sb.classList.toggle('active', v === 'shot');
    const tree = $('pickTree'), wrap = $('shotWrap');
    if (tree) tree.hidden = v !== 'list';
    if (wrap) wrap.hidden = v !== 'shot';
    if (v === 'shot') ensureShot();
  }

  /* ---------- 详情 ---------- */
  function renderDetail() {
    const box = $('pickDetail');
    if (!box) return;
    if (target && target.kind === 'swipe') { renderSwipeDetail(); return; }
    const isCoord = target && target.kind === 'coord';
    if (!selected && !coord) {
      box.innerHTML = '<div class="pick-empty">点一个控件；或切到「截图」后点空白处取坐标</div>';
      return;
    }

    let html = '';
    if (selected) {
      const n = selected;
      html += '<div class="pd-head">' + esc(shortClass(n.className)) + ' <span class="muted">' + esc(n.text || n.desc || '') + '</span></div>'
        + '<div class="pd-kv"><span>id</span><b>' + esc(n.id || '—') + '</b></div>'
        + '<div class="pd-kv"><span>desc</span><b>' + esc(n.desc || '—') + '</b></div>'
        + '<div class="pd-kv"><span>bounds</span><b>' + esc([n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom].join(', ')) + '</b></div>'
        + '<div class="pd-kv"><span>center</span><b>' + esc(n.center.join(', ')) + '</b></div>';
    }
    if (coord) {
      html += '<div class="pd-kv"><span>取点</span><b>' + esc(coord.x + ', ' + coord.y) + '</b></div>';
    }

    if (isCoord) {
      html += '<div class="pd-note">坐标模式：写入 ' + (coord ? '截图取点 ' + coord.x + ', ' + coord.y : '节点中心 ' + (selected ? selected.center.join(', ') : '—')) + '。分辨率变了就会失效。</div>';
    } else if (selected) {
      if (!cands.length) html += '<div class="pick-empty">正在生成候选…</div>';
      else html += cands.map(c => {
        const badge = c.count === 1 ? '<span class="cd ok">唯一</span>'
          : c.count == null ? '<span class="cd">…</span>'
            : c.count === -1 ? '<span class="cd warn">错误</span>'
              : '<span class="cd warn">' + c.count + ' 个</span>';
        return '<div class="cand' + (c === chosen ? ' chosen' : '') + '">'
          + '<code>' + esc(toExpr(c)) + '</code>' + badge
          + '<span class="score" title="稳定性评分">' + c.score + '</span></div>';
      }).join('');
    }

    if (!isCoord && selected) {
      if (chosen && chosen.score < 60) {
        html += '<div class="pd-note">评分 ' + chosen.score + '：换版本或换分辨率可能失效，建议优先用 id，或加 text 兜底。</div>';
      } else if (!chosen && cands.length) {
        const best = cands.slice().sort((a, b) => b.score - a.score)[0];
        html += '<div class="pd-note">没有唯一选择器：最高分候选 ' + best.score + '（' + (best.count == null ? '?' : best.count) + ' 个命中）。强制写入可能点错，建议改用 id、缩小区域，或直接取坐标。</div>';
      }
    }

    const canApply = isCoord ? !!(coord || selected) : !!chosen;
    html += '<div class="pd-actions">'
      + '<button id="pickApply" class="btn primary xs"' + (canApply ? '' : ' disabled') + '>'
      + (isCoord ? '用这个坐标填入' : '填入积木') + '</button>'
      + (isCoord ? '' : '<button id="pickForce" class="btn ghost xs"' + (selected ? '' : ' disabled') + '>强制写入最稳候选</button>')
      + (isCoord ? '' : '<button id="pickApplyExpr" class="btn ghost xs"' + (canApply ? '' : ' disabled') + '>填入为表达式</button>')
      + (isCoord ? '' : '<button id="pickExprChain" class="btn ghost xs"' + (cands.filter(c => c.count !== null && c.count >= 0).length > 1 ? '' : ' disabled') + '>填入兜底表达式</button>')
      + '<button id="pickCopy" class="btn ghost xs"' + (selected ? '' : ' disabled') + '>复制节点 JSON</button>'
      + (coord ? '<button id="pickCopyCoord" class="btn ghost xs">复制坐标</button>' : '')
      + '</div>';
    box.innerHTML = html;

    const a = $('pickApply'); if (a) a.onclick = () => apply(false);
    const f = $('pickForce'); if (f) f.onclick = () => apply(true);
    const ae = $('pickApplyExpr'); if (ae) ae.onclick = () => applyExpr(false);
    const ec = $('pickExprChain'); if (ec) ec.onclick = () => applyExpr(true);
    const c = $('pickCopy'); if (c) c.onclick = copyNode;
    const cc = $('pickCopyCoord'); if (cc) cc.onclick = copyCoord;
  }

  function renderSwipeDetail() {
    const box = $('pickDetail');
    if (!box) return;
    const a = pts && pts.a, b = pts && pts.b;
    let html = '<div class="pd-head">滑动起止点</div>'
      + '<div class="pd-kv"><span>起点</span><b>' + (a ? a.x + ', ' + a.y : '未取') + '</b></div>'
      + '<div class="pd-kv"><span>终点</span><b>' + (b ? b.x + ', ' + b.y : '未取') + '</b></div>';
    if (a && b) {
      const dx = b.x - a.x, dy = b.y - a.y;
      html += '<div class="pd-kv"><span>位移</span><b>' + dx + ', ' + dy + ' · 距离 ' + Math.round(Math.sqrt(dx * dx + dy * dy)) + 'px</b></div>';
    }
    html += '<div class="pd-note">在截图上点两下取起止点，也可以点两个控件用它们的中心；第三下会重新取起点。</div>'
      + '<div class="pd-actions">'
      + '<button id="pickApply" class="btn primary xs"' + (a && b ? '' : ' disabled') + '>填入起止坐标</button>'
      + '<button id="pickReset" class="btn ghost xs">重取</button>'
      + '</div>';
    box.innerHTML = html;
    const ap = $('pickApply'); if (ap) ap.onclick = () => apply(false);
    const rs = $('pickReset');
    if (rs) rs.onclick = () => { pts = { a: null, b: null }; renderShot(); renderDetail(); };
  }

  function showError(e) {
    const box = $('pickDetail');
    const msg = String((e && e.message) || e);
    if (box) {
      box.innerHTML = '<div class="pick-error">连接真机失败：' + esc(msg) + '<div class="muted" style="margin-top:4px">地址：' + esc(A6.bridge.getUrl() || '（未配置）') + '</div>'
        + '<div class="muted" style="margin-top:6px">检查「真机」页的 MCP 地址；跨域被拦时先启动 bridge/mcp-bridge.mjs。</div>'
        + '<button id="pickGoDevice" class="btn xs" style="margin-top:8px">去真机页</button></div>';
      const b = $('pickGoDevice');
      if (b) b.onclick = () => { A6.ui.showTab('device'); close(); };
    }
    const tree = $('pickTree');
    if (tree) tree.innerHTML = '<div class="pick-empty">未取到界面树</div>';
  }

  /* ---------- 取树 ---------- */
  async function refresh() {
    if (!target || busy) return;
    busy = true;
    selected = null; cands = []; chosen = null; coord = null; shot = null;
    const tree = $('pickTree');
    if (tree) tree.innerHTML = '<div class="pick-empty">正在抓取界面…</div>';
    const detail = $('pickDetail');
    if (detail) detail.innerHTML = '';
    try {
      /* 地址只在不冲突时采用输入框的值，避免把已连上的地址覆盖回默认值 */
      const urlEl = $('bridgeUrl');
      const inputUrl = urlEl ? urlEl.value.trim() : '';
      if (!A6.bridge.getUrl() && inputUrl) A6.bridge.setUrl(inputUrl);
      if (!A6.bridge.isReady()) await A6.bridge.ping();
      const data = await A6.bridge.uiDump({ format: 'json', maxNodes: 400, maxDepth: 32, visibleOnly: true });
      meta = data.window || {};
      nodes = (data.nodes || []).map(normalize);
      region = null; fullNodes = null;
      computeDevice();
      dumpSeq++;
      setMeta((meta.packageName || '') + ' · ' + nodes.length + ' 节点' + (data.truncated ? ' · 已截断' : ''));
      renderTree();
      renderDetail();
      updateScope();
      if (view === 'shot') ensureShot();
      if (data.truncated) {
        if (detail) detail.innerHTML = '<div class="pd-note">界面树被截断（上限 400 节点）。用过滤框缩小范围，或切到「截图」按可见区域点选。</div>';
      }
    } catch (e) {
      showError(e);
      setMeta('抓取失败');
    } finally {
      busy = false;
    }
  }

  /* ---------- 校验 ---------- */
  async function verify(n) {
    cands = buildCandidates(n).map(c => Object.assign({}, c, { count: null, score: scoreOf(c, n, 0) }));
    chosen = null;
    renderDetail();
    for (const c of cands) {
      try {
        const f = await A6.bridge.uiFind(c.sel, 20);
        c.count = f.count;
      } catch (e) {
        c.count = -1; c.err = e.message;
      }
      c.score = scoreOf(c, n, c.count);
      if (c.count === 1 && !chosen) chosen = c;
      renderDetail();
      if (chosen) break;
    }
  }

  function bestOf(list) {
    const uniq = list.filter(c => c.count === 1).sort((a, b) => b.score - a.score);
    if (uniq.length) return uniq[0];
    return list.slice().sort((a, b) => b.score - a.score)[0];
  }

  function pickNode(n) {
    selected = n;
    renderTree();
    if (view === 'shot') renderShot();
    if (target && target.kind === 'swipe') { takePoint(n.center); return; }
    if (target && target.kind === 'coord') renderDetail();
    else verify(n);
  }

  /* 两点取点：第一下落起点，第二下落终点，第三下重新落起点 */
  function takePoint(p) {
    if (!pts) return;
    const x = Array.isArray(p) ? p[0] : p.x;
    const y = Array.isArray(p) ? p[1] : p.y;
    if (!pts.a) pts.a = { x, y };
    else if (!pts.b) pts.b = { x, y };
    else { pts.a = { x, y }; pts.b = null; }
    renderShot(); renderDetail();
    if (target && target.kind === 'swipe') {
      A6.ui.toast(pts.b
        ? '起止点已取：' + pts.a.x + ',' + pts.a.y + ' → ' + pts.b.x + ',' + pts.b.y
        : '起点已取：' + pts.a.x + ',' + pts.a.y + '，再点一下取终点');
    }
  }

  /* ---------- 回填 ---------- */
  /* 兜底链：A.exists() ? A : B… —— 文案变了还有第二个选择器兜住 */
  function chainExpr() {
    const list = cands.filter(c => c.count !== null && c.count >= 0);
    const seen = new Set(), exprs = [];
    for (const c of list) {
      const e = toExpr(c);
      if (seen.has(e)) continue;
      seen.add(e);
      exprs.push(e);
      if (exprs.length >= 3) break;
    }
    if (!exprs.length) return null;
    let out = exprs[exprs.length - 1];
    for (let i = exprs.length - 2; i >= 0; i--) out = exprs[i] + '.exists() ? ' + exprs[i] + ' : ' + out;
    return out;
  }

  async function applyExpr(chain) {
    if (!target || target.kind !== 'selector') return;
    const expr = chain
      ? chainExpr()
      : (chosen ? toExpr(chosen) : (cands.length ? toExpr(bestOf(cands)) : null));
    if (!expr) return;
    const found = S.blockById(target.blockId);
    if (!found) { A6.ui.toast('目标积木已被删除'); return; }
    S.snapshot('写入选择器表达式');
    found.block.params.expr = expr;
    S.save();
    A6.ui.commit('写入选择器表达式');
    A6.ui.showTab('code');
    A6.editor.select(found.block.id);
    A6.ui.toast('已写入表达式' + (chosen ? '' : '（非唯一）') + '：' + (expr.length > 44 ? expr.slice(0, 44) + '…' : expr));
    close();
  }

  async function apply(force) {
    if (!target) return;
    if (target.kind === 'swipe') {
      if (!pts || !pts.a || !pts.b) return;
      const f0 = S.blockById(target.blockId);
      if (!f0) { A6.ui.toast('目标积木已被删除'); return; }
      S.snapshot('拾取滑动坐标');
      f0.block.params.x1 = pts.a.x; f0.block.params.y1 = pts.a.y;
      f0.block.params.x2 = pts.b.x; f0.block.params.y2 = pts.b.y;
      S.save();
      A6.ui.commit('拾取滑动坐标');
      A6.ui.showTab('code');
      A6.editor.select(f0.block.id);
      A6.ui.toast('已写入滑动：(' + pts.a.x + ',' + pts.a.y + ') → (' + pts.b.x + ',' + pts.b.y + ')');
      close();
      return;
    }
    const isCoord = target.kind === 'coord';
    if (isCoord && !coord && !selected) return;
    if (!isCoord && !selected) return;
    const c = isCoord ? null : (chosen || (force ? bestOf(cands) : null));
    if (!isCoord && !c) return;

    try {
      const w = await A6.bridge.currentWindow();
      const now = (w.packageName || w.package || '') + '/' + (w.activityName || w.activity || '');
      const was = (meta.packageName || '') + '/' + (meta.activityName || '');
      if (now && was && now !== was && !force) {
        if (!confirm('界面已从\n' + was + '\n切换到\n' + now + '\n，仍要写入吗？')) return;
      }
    } catch (e) { /* 拿不到窗口信息时不阻塞写入 */ }

    const found = S.blockById(target.blockId);
    if (!found) { A6.ui.toast('目标积木已被删除'); return; }
    S.snapshot('拾取控件');
    if (isCoord) {
      const pt = coord || selected.center;
      found.block.params.x = pt[0] !== undefined ? pt[0] : pt.x;
      found.block.params.y = pt[1] !== undefined ? pt[1] : pt.y;
    } else {
      found.block.params.by = c.by;
      found.block.params.value = c.value;
    }
    S.save();
    A6.ui.commit('拾取控件');
    A6.ui.showTab('code');
    A6.editor.select(found.block.id);
    const pt = coord || (selected && selected.center) || [0, 0];
    A6.ui.toast(isCoord
      ? '已写入坐标：' + (pt[0] !== undefined ? pt[0] + ', ' + pt[1] : pt.x + ', ' + pt.y)
      : '已填入：' + c.by + ' = ' + c.value + (c.count === 1 ? '' : '（非唯一，谨慎使用）'));
    close();
  }

  async function copyNode() {
    if (!selected) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(selected, null, 2));
      A6.ui.toast('节点 JSON 已复制');
    } catch (e) { A6.ui.toast('复制失败'); }
  }

  async function copyCoord() {
    if (!coord) return;
    try {
      await navigator.clipboard.writeText('click(' + coord.x + ', ' + coord.y + ');');
      A6.ui.toast('坐标已复制');
    } catch (e) { A6.ui.toast('复制失败'); }
  }

  /* ---------- 生命周期 ---------- */
  function describe(t) {
    const f = S.blockById(t.blockId);
    const name = f ? A6.editor.cleanName(f.block) : t.blockId;
    const label = t.kind === 'coord' ? '拾取坐标：' : t.kind === 'swipe' ? '拾取滑动起止点：' : '拾取控件：';
    return label + name;
  }

  function bind() {
    if (bound) return;
    bound = true;
    const closeBtn = $('pickClose'); if (closeBtn) closeBtn.onclick = close;
    const refreshBtn = $('pickRefresh'); if (refreshBtn) refreshBtn.onclick = refresh;
    const search = $('pickSearch');
    if (search) search.oninput = e => { query = e.target.value; renderTree(); if (view === 'shot') renderShot(); };
    const only = $('pickOnlyClick');
    if (only) only.onchange = e => { onlyClick = e.target.checked; renderTree(); if (view === 'shot') renderShot(); };
    const lb = $('pickViewList'); if (lb) lb.onclick = () => setView('list');
    const sb = $('pickViewShot'); if (sb) sb.onclick = () => setView('shot');
    bindShot();
  }

  function open(t) {
    target = t;
    bind();
    const panel = $('picker');
    if (!panel) return;
    panel.classList.add('open');
    const tg = $('pickTarget');
    if (tg) tg.textContent = describe(t);
    query = ''; onlyClick = false; coord = null;
    pts = t.kind === 'swipe' ? { a: null, b: null } : null;
    const search = $('pickSearch'); if (search) search.value = '';
    const only = $('pickOnlyClick'); if (only) only.checked = false;
    setMeta('');
    setView('list');
    refresh();
  }

  function close() {
    const panel = $('picker');
    if (panel) panel.classList.remove('open');
    target = null; selected = null; cands = []; chosen = null; coord = null; pts = null;
  }

  function isOpen() {
    const panel = $('picker');
    return !!(panel && panel.classList.contains('open'));
  }

  A6.picker = {
    open, close, isOpen, refresh, setView, drill, clearRegion,
    pure: { normalize, buildCandidates, scoreOf, toExpr, shortClass, shortId }
  };
})();