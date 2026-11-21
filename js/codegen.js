/* AutoJs6 Studio · 代码生成
 * 支持两种输出：
 *   A6.gen.code(project)                          → 干净脚本（导出 / 真机运行）
 *   A6.gen.code(project, {markers:true})          → 每块前插一行 `//@积木id`，供编辑器做「点积木→高亮代码」
 */
(function () {
  const IND = '  ';

  function indentLines(text, level) {
    if (!text) return '';
    const pad = IND.repeat(level);
    return text.split('\n').map(l => (l ? pad + l : l)).join('\n');
  }

  function makeCtx(project, opts) {
    opts = opts || {};
    let n = 0;
    const mods = project.modules || [];
    return {
      project: project,
      markers: !!opts.markers,
      v(b, key) {
        const d = A6.catalog.byType(b.type);
        const def = d && d.params.find(p => p.key === key);
        const raw = b.params ? b.params[key] : undefined;
        return raw === undefined || raw === null || raw === '' ? (def ? def.default : '') : raw;
      },
      q(s) { return JSON.stringify(String(s == null ? '' : s)); },
      nv(prefix) { return '_' + (prefix || 'v') + (++n); },
      has(b, slot) { return ((b.children || {})[slot] || []).length > 0; },
      body(b, slot, level) {
        const lv = level === undefined || level === true ? 1 : (level === false ? 0 : Number(level));
        return indentLines(genList((b.children || {})[slot] || [], this.project, this), lv);
      },
      sel(b) {
        /* 表达式优先：填入 expr 时直接用它（括号包裹，避免与 .findOne() 结合时优先级出错） */
        const ex = b.params && b.params.expr;
        if (ex && String(ex).trim()) return '(' + String(ex).trim() + ')';
        const by = b.params.by || 'text';
        const value = b.params.value == null ? '' : b.params.value;
        if (by === 'id') return 'id(' + this.q(value) + ')';
        if (by === 'className') return 'className(' + this.q(value) + ')';
        if (by === 'textContains') return 'textContains(' + this.q(value) + ')';
        if (by === 'textMatches') return 'textMatches(' + this.q(value) + ')';
        if (by === 'descContains') return 'descContains(' + this.q(value) + ')';
        if (by === 'desc') return 'desc(' + this.q(value) + ')';
        if (by === 'idMatches') return 'idMatches(' + this.q(value) + ')';
        if (by === 'descMatches') return 'descMatches(' + this.q(value) + ')';
        if (by === 'classNameMatches') return 'classNameMatches(' + this.q(value) + ')';
        return 'text(' + this.q(value) + ')';
      },
      callModule(b) {
        const m = mods.find(x => x.id === b.params.module || x.name === b.params.module);
        if (!m) return '// 未选择模块';
        return moduleName(m) + '(' + (b.params.args || '') + ');';
      }
    };
  }

  function moduleName(m) {
    return 'mod_' + String(m.name).replace(/[^\w\u4e00-\u9fa5]/g, '_');
  }

  function genBlock(b, project, ctx) {
    const d = A6.catalog.byType(b.type);
    if (!d || !d.gen) return '';
    const c = ctx || makeCtx(project);
    let out = '';
    try { out = d.gen(b, c) || ''; } catch (e) { out = '// 生成失败: ' + b.type + ' (' + e.message + ')'; }
    return out;
  }

  function genList(list, project, ctx) {
    return (list || []).map(b => {
      const t = genBlock(b, project, ctx);
      if (!t) return '';
      return ctx && ctx.markers ? '//@' + b.id + '\n' + t : t;
    }).filter(Boolean).join('\n');
  }

  function header(p) {
    const pad = n => String(n).padStart(2, '0');
    const d = new Date();
    return [
      '/**',
      ' * ' + (p.name || 'AutoJs6 脚本'),
      ' * 由 AutoJs6 Studio 可视化积木生成 · ' + d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()),
      ' * 目标运行时：AutoJs6 6.x（Rhino）· 语法遵循 AutoJs6 全局 API',
      ' */',
      '"auto";'
    ].join('\n');
  }

  function modulesCode(p, ctx) {
    return (p.modules || []).map(m => {
      const body = genList(m.children.body, p, ctx);
      return '/** 模块：' + (m.desc || m.name) + ' */\nfunction ' + moduleName(m) + '(' + (m.args || '') + ') {\n'
        + indentLines(body || '// 空模块', 1) + '\n}';
    }).join('\n\n');
  }

  A6.gen = {
    code(project, opts) {
      const p = project || A6.store.state.project;
      const ctx = makeCtx(p, opts || {});
      const parts = [header(p)];
      const mc = modulesCode(p, ctx);
      if (mc) parts.push(mc);
      const main = genList(p.root.children.body, p, ctx);
      parts.push(main || '// 主程序为空：从左侧拖入积木开始');
      return parts.join('\n\n') + '\n';
    },
    block(b, project) { return genBlock(b, project || A6.store.state.project); },
    moduleName
  };
})();