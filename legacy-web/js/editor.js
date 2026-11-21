/* AutoJs6 Studio · 编辑器（积木库、画布、拖拽、参数绑定） */
(function () {
  const S = A6.store;
  let dragPayload = null;

  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));
  const el = (tag, cls, html) => { const n = document.createElement(tag); if (cls) n.className = cls; if (html != null) n.innerHTML = html; return n; };

  function cleanName(b) {
    const d = A6.catalog.byType(b.type);
    return d ? d.name.replace(/\{[^}]*\}/g, '').replace(/\s+/g, ' ').trim() : b.type;
  }
  function catColor(cat) { return (A6.catalog.colors()[cat] || '#4f8cff'); }

  /* 哪些参数支持「从真机拾取」 */
  const PICK_BLOCKS = { clickNode: 1, longClickNode: 1, setTextNode: 1, nodeTextVar: 1, existsVar: 1, scrollNode: 1 };
  function pickKind(b, pd) {
    if (pd.key === 'value' && PICK_BLOCKS[b.type]) return 'selector';
    if (b.type === 'coordClick' && (pd.key === 'x' || pd.key === 'y')) return 'coord';
    if (b.type === 'swipe' && (pd.key === 'x1' || pd.key === 'y1' || pd.key === 'x2' || pd.key === 'y2')) return 'swipe';
    return null;
  }

  /* ---------- 定位 ---------- */
  function locate(id, list, parentId, slot) {
    list = list || S.state.project.root.children.body;
    for (let i = 0; i < list.length; i++) {
      if (list[i].id === id) return { list, parentId: parentId || 'root', slot: slot || 'body', index: i };
      for (const k of Object.keys(list[i].children || {})) {
        const r = locate(id, list[i].children[k], list[i].id, k);
        if (r) return r;
      }
    }
    return null;
  }
  function locateAny(id) {
    let r = locate(id, S.state.project.root.children.body, 'root', 'body');
    if (r) return r;
    for (const m of S.state.project.modules) {
      r = locate(id, m.children.body, 'mod:' + m.id, 'body');
      if (r) return r;
    }
    return null;
  }
  function containerList(parentId, slot) {
    if (parentId === 'root') return S.state.project.root.children.body;
    if (String(parentId).indexOf('mod:') === 0) {
      const m = S.state.project.modules.find(x => x.id === parentId.slice(4));
      return m ? m.children.body : null;
    }
    const f = S.blockById(parentId);
    if (!f) return null;
    if (!f.block.children[slot]) f.block.children[slot] = [];
    return f.block.children[slot];
  }
  function isAncestor(id, childParentId) {
    if (String(childParentId).indexOf('mod:') === 0) return false;
    let cur = S.blockById(childParentId);
    while (cur) {
      if (cur.block.id === id) return true;
      cur = S.blockById(cur.parent ? cur.parent.id : null);
    }
    return false;
  }

  /* ---------- 积木库 ---------- */
  function renderPalette(filter) {
    const wrap = document.getElementById('palette');
    const q = (filter || '').trim().toLowerCase();
    wrap.innerHTML = '';
    A6.catalog.CATS.forEach(cat => {
      let defs = A6.catalog.byCat(cat.id);
      if (q) defs = defs.filter(d => (d.name + ' ' + d.type).toLowerCase().indexOf(q) >= 0);
      if (!defs.length) return;
      const box = el('div', 'cat');
      const head = el('div', 'cat-head');
      head.innerHTML = '<span class="cat-dot" style="--c:' + cat.color + '"></span><span>' + esc(cat.name) + '</span><span class="cat-arrow">▾</span>';
      head.onclick = () => box.classList.toggle('closed');
      const items = el('div', 'cat-items');
      defs.forEach(d => {
        const it = el('div', 'pal-item');
        it.draggable = true;
        it.style.setProperty('--c', cat.color);
        it.innerHTML = '<span class="pi-cat"></span><span class="pi-name">' + esc(d.name.replace(/\{[^}]*\}/g, '').trim()) + '</span>';
        it.title = d.type;
        it.addEventListener('dragstart', e => {
          dragPayload = { mode: 'new', type: d.type };
          e.dataTransfer.setData('text/plain', JSON.stringify(dragPayload));
          e.dataTransfer.effectAllowed = 'copy';
          it.classList.add('dragging');
        });
        it.addEventListener('dragend', () => it.classList.remove('dragging'));
        it.onclick = () => addNew(d.type);
        items.appendChild(it);
      });
      box.appendChild(head); box.appendChild(items);
      if (q) box.classList.remove('closed');
      wrap.appendChild(box);
    });
    if (!wrap.children.length) wrap.appendChild(el('div', 'empty-hint', '没有匹配的积木'));
  }

  /* ---------- 画布 ---------- */
  function scopeRootId() {
    return S.state.scope.kind === 'module' ? 'mod:' + S.state.scope.id : 'root';
  }

  function renderCanvas() {
    const canvas = document.getElementById('canvas');
    canvas.innerHTML = '';
    const inner = el('div', 'canvas-inner');

    const mod = S.currentModule();
    if (mod) {
      const head = el('div', 'scope-title');
      head.innerHTML = '<span>模块</span>';
      inner.appendChild(head);
      const box = el('div', 'block');
      box.style.setProperty('--c', '#64748b');
      box.style.marginBottom = '12px';
      const bd = el('div', 'b-head');
      bd.style.flexWrap = 'wrap';
      bd.innerHTML = '<span class="b-name">模块定义</span>';
      const nameIn = el('input', 'pin'); nameIn.value = mod.name; nameIn.style.cssText = 'background:#0f141c;border:1px solid #2b3646;border-radius:6px;padding:3px 7px;outline:none;width:130px';
      nameIn.oninput = () => { mod.name = nameIn.value; S.save(); A6.ui.refreshCode(); };
      const argsIn = el('input', 'pin'); argsIn.value = mod.args || ''; argsIn.placeholder = '参数，如 pkg'; argsIn.style.cssText = nameIn.style.cssText + ';width:130px';
      argsIn.oninput = () => { mod.args = argsIn.value; S.save(); A6.ui.refreshCode(); };
      const del = el('button', 'btn ghost xs', '删除模块');
      del.onclick = () => {
        S.snapshot('删除模块');
        S.state.project.modules = S.state.project.modules.filter(x => x.id !== mod.id);
        S.state.scope = { kind: 'root' };
        S.save(); A6.ui.renderAll();
        A6.ui.toast('已删除模块');
      };
      bd.appendChild(nameIn); bd.appendChild(argsIn); bd.appendChild(del);
      box.appendChild(bd);
      inner.appendChild(box);
    } else {
      inner.appendChild(el('div', 'scope-title', '<b>主程序</b><span>从上到下执行</span>'));
    }

    const list = S.state.scope.kind === 'module' ? (mod ? mod.children.body : []) : S.state.project.root.children.body;
    const listBox = el('div');
    renderList(list, scopeRootId(), 'body', listBox, true);
    inner.appendChild(listBox);
    canvas.appendChild(inner);
    updateStats();
    if (A6.ui && A6.ui.highlightCode) A6.ui.highlightCode(S.state.selected, false);
  }

  function renderList(list, parentId, slot, container, isRoot) {
    list.forEach((b, i) => {
      container.appendChild(makeDropzone(parentId, slot, i));
      container.appendChild(blockEl(b));
    });
    const end = makeDropzone(parentId, slot, list.length);
    if (isRoot) {
      end.classList.add('root-end');
      end.textContent = list.length ? '拖到这里追加积木' : '把左侧积木拖到这里，或直接点击积木';
    }
    container.appendChild(end);
  }

  function makeDropzone(parentId, slot, index) {
    const dz = el('div', 'dz');
    dz.dataset.parent = parentId; dz.dataset.slot = slot; dz.dataset.index = index;
    dz.addEventListener('dragover', e => { e.preventDefault(); e.dataTransfer.dropEffect = dragPayload && dragPayload.mode === 'move' ? 'move' : 'copy'; dz.classList.add('dragover'); });
    dz.addEventListener('dragleave', () => dz.classList.remove('dragover'));
    dz.addEventListener('drop', e => {
      e.preventDefault(); dz.classList.remove('dragover');
      let p = dragPayload;
      try { p = JSON.parse(e.dataTransfer.getData('text/plain')) || p; } catch (err) { }
      if (!p) return;
      if (p.mode === 'new') {
        const blk = S.mkBlock(p.type);
        insertAt(blk, parentId, slot, index);
        S.state.selected = blk.id;
        A6.ui.commit('添加积木');
      } else if (p.mode === 'move') {
        moveTo(p.id, parentId, slot, index);
      }
      dragPayload = null;
    });
    return dz;
  }

  function blockEl(b) {
    const d = A6.catalog.byType(b.type);
    if (!d) return el('div', 'block', '未知积木：' + esc(b.type));
    const box = el('div', 'block' + (S.state.selected === b.id ? ' selected' : ''));
    box.dataset.id = b.id;
    box.style.setProperty('--c', catColor(d.cat));

    const head = el('div', 'b-head');
    const handle = el('span', 'handle', '⠿');
    handle.draggable = true;
    handle.title = '拖动以移动';
    handle.addEventListener('dragstart', e => {
      dragPayload = { mode: 'move', id: b.id };
      e.dataTransfer.setData('text/plain', JSON.stringify(dragPayload));
      e.dataTransfer.effectAllowed = 'move';
    });
    head.appendChild(handle);

    const name = el('span', 'b-name', esc(cleanName(b)));
    head.appendChild(name);
    if (b.params && b.params.expr && String(b.params.expr).trim()) head.appendChild(el('span', 'b-tag', 'fx'));

    const params = el('span', 'b-params');
    (d.params || []).forEach(pd => params.appendChild(paramEl(b, pd)));
    head.appendChild(params);

    const acts = el('span', 'b-actions');
    const mk = (label, title, fn, cls) => { const x = el('button', cls || '', label); x.title = title; x.onclick = ev => { ev.stopPropagation(); fn(); }; acts.appendChild(x); return x; };
    mk('↑', '上移', () => shift(b.id, -1));
    mk('↓', '下移', () => shift(b.id, 1));
    mk('⧉', '复制', () => duplicate(b.id));
    mk('✕', '删除', () => remove(b.id), 'del');
    head.appendChild(acts);

    /* 点积木卡片任意空白处 = 选中（参数框、按钮、拖动柄除外；子积木自己处理） */
    box.addEventListener('click', e => {
      if (e.target.closest('.p') || e.target.closest('.b-actions') || e.target.closest('.handle')) return;
      if (e.target.closest('.block') !== box) return;
      select(b.id);
    });

    box.appendChild(head);

    if (d.slots && d.slots.length) {
      const slotsBox = el('div', 'b-slots');
      d.slots.forEach(sl => {
        const sb = el('div', 'slot');
        sb.appendChild(el('div', 'slot-head', '↳ ' + esc(sl.label)));
        const inner = el('div');
        const kids = (b.children || {})[sl.key] || ((b.children || {})[sl.key] = []);
        renderList(kids, b.id, sl.key, inner, false);
        sb.appendChild(inner);
        slotsBox.appendChild(sb);
      });
      box.appendChild(slotsBox);
    }
    return box;
  }

  function paramEl(b, pd) {
    const wrap = el('span', 'p');
    let val = b.params ? b.params[pd.key] : undefined;
    if (val === undefined || val === null) val = pd.default;

    if (pd.kind === 'checkbox') {
      wrap.innerHTML = '<label class="chk"><input type="checkbox" ' + (val ? 'checked' : '') + '><span>' + esc(pd.label) + '</span></label>';
      const cb = wrap.querySelector('input');
      cb.onchange = () => { b.params[pd.key] = cb.checked; S.save(); A6.ui.refreshCode(); A6.ui.scheduleDiag(); };
      return wrap;
    }

    wrap.appendChild(el('span', 'plabel', esc(pd.label)));
    let input;
    if (pd.kind === 'select') {
      input = el('select');
      let opts = pd.options || [];
      if (typeof opts === 'function') opts = opts(S.state.project);
      if (b.type === 'moduleCall' && pd.key === 'module') {
        opts = [{ v: '', t: '选择模块' }].concat((S.state.project.modules || []).map(m => ({ v: m.id, t: m.name })));
      }
      opts.forEach(o => {
        const op = el('option'); op.value = o.v; op.textContent = o.t;
        if (String(o.v) === String(val)) op.selected = true;
        input.appendChild(op);
      });
      if (!opts.length) { const op = el('option'); op.textContent = '（无）'; input.appendChild(op); }
      input.onchange = () => { S.snapshot('修改参数'); b.params[pd.key] = input.value; S.save(); A6.ui.refreshCode(); A6.ui.scheduleDiag(); };
    } else {
      input = el('input', 'pin' + (pd.kind === 'number' ? ' num' : '') + (pd.kind === 'code' || pd.kind === 'expr' ? ' code' : ''));
      input.value = val == null ? '' : val;
      input.placeholder = pd.label;
      if (pd.kind === 'number') input.type = 'number';
      input.addEventListener('focus', () => { if (!input.__snap) { S.snapshot('修改参数'); input.__snap = true; } });
      input.oninput = () => {
        b.params[pd.key] = pd.kind === 'number' ? Number(input.value) : input.value;
        S.save(); A6.ui.refreshCode(); A6.ui.scheduleDiag();
      };
    }
    wrap.appendChild(input);
    const pk = pickKind(b, pd);
    if (pk) {
      const btn = el('button', 'pickbtn', '⌖');
      btn.title = '从真机拾取（连接 MCP 后可用）';
      btn.onclick = ev => {
        ev.preventDefault(); ev.stopPropagation();
        if (A6.picker) A6.picker.open({ blockId: b.id, paramKey: pd.key, kind: pk });
      };
      wrap.appendChild(btn);
    }
    return wrap;
  }

  /* ---------- 变更 ---------- */
  function insertAt(blk, parentId, slot, index) {
    const list = containerList(parentId, slot);
    if (!list) return false;
    const i = Math.max(0, Math.min(Number(index) || 0, list.length));
    list.splice(i, 0, blk);
    return true;
  }

  function addNew(type) {
    S.snapshot('添加积木');
    const blk = S.mkBlock(type);
    const sel = S.state.selected;
    const def = A6.catalog.byType(type);
    let target = null;
    if (sel) {
      const f = S.blockById(sel);
      if (f) {
        const fd = A6.catalog.byType(f.block.type);
        if (fd.slots && fd.slots.length) {
          const key = fd.slots[0].key;
          if (!f.block.children[key]) f.block.children[key] = [];
          target = { parentId: f.block.id, slot: key, index: f.block.children[key].length };
        } else {
          const loc = locateAny(sel);
          if (loc) target = { parentId: loc.parentId, slot: loc.slot, index: loc.index + 1 };
        }
      }
    }
    if (!target) {
      const list = S.state.scope.kind === 'module' ? (S.currentModule() || { children: { body: [] } }).children.body : S.state.project.root.children.body;
      target = { parentId: scopeRootId(), slot: 'body', index: list.length };
    }
    insertAt(blk, target.parentId, target.slot, target.index);
    S.state.selected = blk.id;
    A6.ui.commit('添加积木');
    A6.ui.toast('已添加：' + cleanName(blk));
    return def;
  }

  function moveTo(id, parentId, slot, index) {
    if (parentId === id || isAncestor(id, parentId)) { A6.ui.toast('不能把积木放进它自己里面'); return; }
    S.snapshot('移动积木');
    const loc = locateAny(id);
    if (!loc) return;
    let adjust = 0;
    if (loc.parentId === parentId && loc.slot === slot && loc.index < Number(index)) adjust = -1;
    const moved = loc.list[loc.index];
    loc.list.splice(loc.index, 1);
    const list = containerList(parentId, slot);
    if (!list) { loc.list.splice(loc.index, 0, moved); return; }
    const i = Math.max(0, Math.min(Number(index) + adjust, list.length));
    list.splice(i, 0, moved);
    S.state.selected = id;
    A6.ui.commit('移动积木');
  }

  function shift(id, delta) {
    const loc = locateAny(id);
    if (!loc) return;
    const j = loc.index + delta;
    if (j < 0 || j >= loc.list.length) return;
    S.snapshot('调整顺序');
    const [b] = loc.list.splice(loc.index, 1);
    loc.list.splice(j, 0, b);
    S.state.selected = id;
    A6.ui.commit('调整顺序');
  }

  function duplicate(id) {
    const loc = locateAny(id);
    if (!loc) return;
    S.snapshot('复制积木');
    const c = S.cloneBlock(loc.list[loc.index]);
    loc.list.splice(loc.index + 1, 0, c);
    S.state.selected = c.id;
    A6.ui.commit('复制积木');
  }

  function remove(id) {
    const loc = locateAny(id);
    if (!loc) return;
    S.snapshot('删除积木');
    loc.list.splice(loc.index, 1);
    if (S.state.selected === id) S.state.selected = null;
    A6.ui.commit('删除积木');
  }

  function select(id) {
    S.state.selected = id;
    document.querySelectorAll('.block').forEach(n => n.classList.toggle('selected', n.dataset.id === id));
    const f = id ? S.blockById(id) : null;
    if (f) {
      const d = A6.catalog.byType(f.block.type);
      A6.ui.setHint(cleanName(f.block) + ' · ' + f.block.type + (d && d.slots.length ? ' · 容器' : ''));
    } else A6.ui.setHint('');
    if (A6.ui && A6.ui.highlightCode) A6.ui.highlightCode(id, true);
  }

  function updateStats() {
    const all = [];
    S.walk(S.state.project.root.children.body, b => all.push(b));
    const n = all.length;
    document.getElementById('statBlocks').textContent = n + ' 块';
    document.getElementById('statModules').textContent = (S.state.project.modules || []).length + ' 模块';
    document.getElementById('statTests').textContent = (S.state.project.tests || []).length + ' 测试';
    document.getElementById('blockCount') && (document.getElementById('blockCount').textContent = n + ' 块');
  }

  function scrollToBlock(id) {
    const n = document.querySelector('.block[data-id="' + id + '"]');
    if (n) { n.scrollIntoView({ block: 'center', behavior: 'smooth' }); select(id); }
  }

  A6.editor = { renderPalette, renderCanvas, select, addNew, remove, duplicate, shift, moveTo, locateAny, containerList, scrollToBlock, updateStats, cleanName, isAncestor };
})();