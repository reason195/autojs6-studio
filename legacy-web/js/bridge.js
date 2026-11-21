/* AutoJs6 Studio · 真机桥接（MCP over HTTP）
 * 目标：把浏览器里生成的脚本送到 AutoJs6 执行，并取回 console 输出。
 * 兼容 MCP Streamable HTTP：initialize → notifications/initialized → tools/call。
 */
(function () {
  const cfg = { url: '', sessionId: '', ready: false, protocol: '2024-11-05', headers: {}, authByUrl: {} };
  let seq = 0;
  const log = (level, msg) => { if (A6.ui && A6.ui.deviceLog) A6.ui.deviceLog(level, msg); };

  function endpoint() {
    const u = (cfg.url || '').trim().replace(/\/+$/, '');
    return u || '';
  }

async function post(payload, extraHeaders, timeoutMs) {
    const url = endpoint();
    if (!url) throw new Error('未填写桥接地址');
    const headers = Object.assign({
      'Content-Type': 'application/json',
      'Accept': 'application/json, text/event-stream'
    }, cfg.headers || {}, extraHeaders || {});
    if (cfg.sessionId) headers['mcp-session-id'] = cfg.sessionId;
    /* 手机侧会"端口在听但不回声"（主线程被占住）；没有超时时 fetch 会永久挂起，后续请求排队，界面看起来像卡死 */
    const ms = Number(timeoutMs) || 15000;
    const ac = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = ac ? setTimeout(() => { try { ac.abort(); } catch (e) { } }, ms) : null;
    let res, text;
    try {
      res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(payload), signal: ac ? ac.signal : undefined });
      if (res.status === 404 || res.status === 405) {
        throw new Error('HTTP ' + res.status + '：地址不是 MCP 端点，或桥接未启动');
      }
      const sid = res.headers.get('mcp-session-id');
      if (sid) cfg.sessionId = sid;
      text = await res.text();
    } catch (e) {
      if (e && e.name === 'AbortError') {
        const err = new Error('手机 MCP 无响应：' + Math.round(ms / 1000) + 's 内没有回包（' + url + '）。'
          + '端口在听却不应答，通常是 AutoJs6 主线程被占满（正在跑的录制脚本、无障碍查询卡住）。'
          + '处理：在手机上按音量下键停掉录制脚本，或从 AutoJs6 停止全部脚本，再重启 MCP Server 后重连。');
        err.code = 'TIMEOUT';
        throw err;
      }
      if (e && /^HTTP 40[45]/.test(String(e.message || ''))) throw e;
      /* fetch 直接抛 TypeError 时浏览器只给 "Failed to fetch"，这里给出可操作的三种可能 */
      const err = new Error('连不上 ' + url + '：' + (e.message || 'Failed to fetch')
        + '。检查：①手机 AutoJs6 的 MCP Server 是否在运行（屏幕别锁、加到后台白名单）；'
        + '②IP 是否变了（重新看手机端点值，改 js/config.js）；'
        + '③本页是否用 file:// 打开或服务端没放行 CORS（改用 http://127.0.0.1:8791 打开，或先跑 node bridge/mcp-bridge.mjs 用代理地址）。');
      err.code = 'NETWORK';
      throw err;
    } finally {
      if (timer) clearTimeout(timer);
    }
    const ct = res.headers.get('content-type') || '';
    if (!text) return null;
    if (ct.indexOf('text/event-stream') >= 0) {
      const lines = text.split(/\r?\n/);
      let last = null;
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (data && data !== '[DONE]') { try { last = JSON.parse(data); } catch (e) { } }
        }
      }
      return last;
    }
    try { return JSON.parse(text); } catch (e) { throw new Error('返回内容不是 JSON：' + text.slice(0, 120)); }
  }

async function rpc(method, params, timeoutMs) {
    const id = ++seq;
    const r = await post({ jsonrpc: '2.0', id, method, params: params || {} }, null, timeoutMs);
    if (!r) throw new Error('空响应');
    if (r.error) throw new Error(r.error.message || JSON.stringify(r.error));
    return r.result;
  }

  async function initialize() {
    const res = await post({
      jsonrpc: '2.0', id: ++seq, method: 'initialize',
      params: { protocolVersion: cfg.protocol, capabilities: {}, clientInfo: { name: 'AutoJs6 Studio', version: '1.0.0' } }
    });
    if (res && res.error) throw new Error(res.error.message || 'initialize 失败');
    try {
      await post({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
    } catch (e) { }
    cfg.ready = true;
    return res && res.result;
  }

  async function ensure() {
    if (!cfg.ready) await initialize();
  }

async function callTool(name, args, timeoutMs) {
    await ensure();
    const r = await rpc('tools/call', { name, arguments: args || {} }, timeoutMs);
    if (!r) throw new Error('工具无响应');
    const content = (r.content || []).map(c => c.text || '').join('\n');
    if (r.isError) throw new Error(content || '工具返回错误');
    return { raw: r, text: content, content: r.content || [] };
  }

  function parseJson(text) {
    try { return JSON.parse(text); } catch (e) { return null; }
  }

  A6.bridge = {
    cfg,
    /* 切设备时自动带回该设备本次会话填过的令牌（仍不落盘） */
    setUrl(u) {
      cfg.url = u || '';
      cfg.ready = false; cfg.sessionId = '';
      cfg.headers = cfg.headers || {};
      const cached = cfg.authByUrl[cfg.url];
      if (cached) cfg.headers.Authorization = cached;
      else delete cfg.headers.Authorization;
    },
    setAuthFor(url, token) {
      const t = String(token == null ? '' : token).trim();
      const k = String(url || '').trim();
      if (!k) return;
      if (!t) { delete cfg.authByUrl[k]; if (cfg.url === k) delete cfg.headers.Authorization; return; }
      const v = /^Bearer\s/i.test(t) ? t : 'Bearer ' + t;
      cfg.authByUrl[k] = v;
      if (cfg.url === k) cfg.headers.Authorization = v;
    },
    authUrls() { return Object.keys(cfg.authByUrl || {}); },
    setAuthMap(map) {
      cfg.authByUrl = Object.assign({}, map || {});
      if (cfg.url) {
        const v = cfg.authByUrl[cfg.url];
        if (v) cfg.headers.Authorization = v;
        else delete cfg.headers.Authorization;
      }
    },
    authMap() { return Object.assign({}, cfg.authByUrl || {}); },
    /* 令牌只放内存：不写进项目文件、不进 localStorage */
    setAuth(token) {
      const t = String(token == null ? '' : token).trim();
      cfg.headers = cfg.headers || {};
      if (!t) delete cfg.headers.Authorization;
      else cfg.headers.Authorization = /^Bearer\s/i.test(t) ? t : 'Bearer ' + t;
      cfg.ready = false; cfg.sessionId = '';
    },
    setHeaders(h) { cfg.headers = Object.assign({}, h || {}); cfg.ready = false; cfg.sessionId = ''; },
    headers() { return Object.assign({}, cfg.headers || {}); },
    getUrl() { return cfg.url; },
    isReady() { return cfg.ready; },
    async ping() {
      cfg.ready = false; cfg.sessionId = '';
      const info = await initialize();
      const t0 = performance.now();
      const r = await callTool('device_ping', {});
      const ms = Math.round(performance.now() - t0);
      log('sys', 'MCP 已连接（' + ms + 'ms）');
      return { info, data: parseJson(r.text) || r.text };
    },
    async deviceInfo() {
      const r = await callTool('device_info', {});
      return parseJson(r.text) || { raw: r.text };
    },
    async runScript(source, name) {
      const r = await callTool('script_run', { source, name: name || 'a6studio_script', timeoutMs: 60000, captureConsole: true, maxConsoleLines: 300 });
      log('sys', '已发送脚本到真机：' + (name || 'a6studio_script'));
      return parseJson(r.text) || { raw: r.text };
    },
    async consoleTail(lines) {
      const r = await callTool('console_tail', { lines: lines || 60 });
      return parseJson(r.text) || { raw: r.text };
    },
    async uiDumpText(opts) {
      const r = await callTool('ui_dump', Object.assign({ format: 'text', maxNodes: 200, maxDepth: 32 }, opts || {}));
      return r.text;
    },
    async uiDump(opts) {
      const r = await callTool('ui_dump', Object.assign({ format: 'json', maxNodes: 400, maxDepth: 32 }, opts || {}));
      const data = parseJson(r.text);
      if (!data || !data.nodes) throw new Error('ui_dump 未返回可解析的 JSON：检查 MCP 是否支持 format=json');
      return data;
    },
    async uiFind(selector, limit) {
      const r = await callTool('ui_find', { selector: selector || {}, limit: limit || 10 });
      const data = parseJson(r.text) || {};
      return { count: Number(data.count || 0), returned: Number(data.returned || 0), truncated: !!data.truncated, nodes: data.nodes || [], raw: r.text };
    },
    async explainSelector(selector) {
      const r = await callTool('ui_explain_selector', { selector: selector || {} });
      return parseJson(r.text) || { raw: r.text };
    },
    /* 截屏：MCP 返回 image content + 一段 JSON metadata；这里统一成 data URL + meta */
    async screenCapture(opts) {
      await ensure();
      const o = Object.assign({ format: 'jpeg', quality: 70, maxWidth: 1080 }, opts || {});
      const r = await rpc('tools/call', { name: 'screen_capture', arguments: o });
      if (!r) throw new Error('截屏无响应');
      const content = r.content || [];
      const txt = content.filter(c => c.type === 'text').map(c => c.text).join('\n');
      const meta = parseJson(txt) || {};
      if (r.isError || meta.retryAfterMs || /RATE_LIMITED/.test(txt)) {
        const err = new Error('截屏失败：' + (txt || '未知错误'));
        err.code = 'RATE_LIMITED';
        err.retryAfterMs = meta.retryAfterMs || 0;
        err.meta = meta;
        throw err;
      }
      const part = content.find(c => c.type === 'image') || content.find(c => c.type === 'resource');
      let data = null, mime = 'image/jpeg';
      if (part && part.type === 'image') { data = part.data; mime = part.mimeType || mime; }
      else if (part && part.resource) { data = part.resource.blob || part.resource.data; mime = part.resource.mimeType || mime; }
      if (!data) {
        const err = new Error('screen_capture 没有返回图像（手机上可能未授予截屏权限）');
        err.code = 'NO_IMAGE';
        throw err;
      }
      return { url: 'data:' + mime + ';base64,' + data, mimeType: mime, meta, text: txt };
    },
    async currentWindow() {
      const r = await callTool('ui_current_window', {});
      return parseJson(r.text) || { raw: r.text };
    },
    async stopAll() {
      const r = await callTool('script_stop_all', {});
      return parseJson(r.text) || { raw: r.text };
    },
    /* 录制：把脚本推到手机（默认不等待结束），再轮询写出的记录文件 */
    async scriptRun(source, opts) {
      const o = Object.assign({ name: 'mcp_script', waitForCompletion: false, captureConsole: false }, opts || {});
      const a = { source: String(source || '') };
      ['name', 'waitForCompletion', 'captureConsole', 'timeoutMs', 'workingDirectory', 'arguments'].forEach(k => { if (o[k] !== undefined) a[k] = o[k]; });
      const r = await callTool('script_run', a, 65000);
      return parseJson(r.text) || { raw: r.text };
    },
    async scriptStop(executionId, timeoutMs) {
      const r = await callTool('script_stop', { executionId: Number(executionId) }, Number(timeoutMs) || 5000);
      return parseJson(r.text) || { raw: r.text };
    },
    async scriptList() {
      const r = await callTool('script_list', {});
      return parseJson(r.text) || { raw: r.text };
    },
    /* files_read 的返回形状随版本不同：优先 JSON，退化为纯文本 */
    async filesRead(path, opts) {
const a = Object.assign({ path: String(path), encoding: 'utf-8' }, opts || {});
      const r = await callTool('files_read', a, 12000);
      const d = parseJson(r.text);
      return d || { content: r.text };
    },
    /* 设备侧 shell（AutoJs6 的 shell 权限，取决于是否有 root/Shizuku） */
    async shellExec(command) {
      const r = await callTool('shell_exec', { cmd: String(command) });
      return parseJson(r.text) || { raw: r.text };
    },
    async filesMkdir(path) {
      const r = await callTool('files_mkdir', { path: String(path) });
      return parseJson(r.text) || { raw: r.text };
    },
    async filesStat(path) {
      const r = await callTool('files_stat', { path: String(path) });
      return parseJson(r.text) || { raw: r.text };
    }
  };
})();