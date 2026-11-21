/**
 * Mock MCP Server（仅用于 P0 验收，不代替真机）
 * 实现：initialize / notifications/initialized / tools/call
 * 工具：device_ping、device_info、ui_dump(json|text)、ui_find、ui_explain_selector、
 *       ui_current_window、ui_click、script_run、console_tail
 *
 * 启动：node tests/mock-mcp.mjs   （默认 127.0.0.1:8792）
 * 页面里把「真机」的地址填 http://127.0.0.1:8792/mcp
 */
import http from 'node:http';

const PORT = Number(process.env.PORT || 8792);

const WINDOW = {
  packageName: 'com.jingdong.app.mall',
  activityName: 'com.jd.lib.ttt.page.TTTMultiPageActivity',
  bounds: { left: 0, top: 0, right: 1080, bottom: 2376 }
};

const DEF = {
  visible: true, enabled: true, longClickable: false, scrollable: false,
  checkable: false, checked: false, editable: false, focused: false, selected: false
};

function n(depth, index, childCount, className, o) {
  const b = o.bounds;
  return Object.assign({}, DEF, {
    depth, index, childCount, className,
    text: o.text || '', desc: o.desc || '', id: o.id || '',
    bounds: b,
    center: [Math.round((b.left + b.right) / 2), Math.round((b.top + b.bottom) / 2)],
    clickable: !!o.clickable,
    editable: !!o.editable,
    scrollable: !!o.scrollable
  }, o);
}

const B = (l, t, r, bt) => ({ left: l, top: t, right: r, bottom: bt });

/* 1x1 PNG：Mock 只验证链路，页面按 device 比例给它定尺寸，真实设备回来的是真截图 */
const TINY_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const NODES = [
  n(0, -1, 2, 'android.widget.FrameLayout', { bounds: B(0, 0, 1080, 2376) }),
  n(1, 0, 4, 'android.widget.LinearLayout', { id: 'com.jingdong.app.mall:id/layout_main', bounds: B(0, 0, 1080, 2328) }),
  n(2, 0, 0, 'android.widget.TextView', { text: '领券中心', bounds: B(60, 120, 420, 200) }),
  n(2, 1, 1, 'android.view.ViewGroup', { id: 'com.jingdong.app.mall:id/coupon-sign-wrap', bounds: B(580, 280, 1020, 440) }),
  n(2, 1, 0, 'android.widget.Button', { text: '天天领豆', id: 'com.jingdong.app.mall:id/coupon-sign', clickable: true, bounds: B(600, 300, 1000, 420) }),
  n(2, 2, 0, 'android.widget.Button', { text: '立即领取', clickable: true, bounds: B(80, 520, 500, 620) }),
  n(2, 3, 0, 'android.widget.Button', { text: '立即领取', clickable: true, bounds: B(560, 520, 1000, 620) }),
  n(1, 1, 2, 'android.view.ViewGroup', { id: 'com.jingdong.app.mall:id/ttt_layout_id_root', bounds: B(0, 700, 1080, 2328) }),
  n(2, 0, 0, 'android.view.View', { desc: '更多任务', id: 'com.jingdong.app.mall:id/coupon-left-entry', clickable: true, bounds: B(40, 760, 520, 860) }),
  n(2, 1, 0, 'android.widget.ImageView', { clickable: true, bounds: B(900, 760, 1040, 860) })
];

function matchNode(node, sel) {
  for (const k of Object.keys(sel || {})) {
    const v = sel[k];
    if (k === 'text') { if ((node.text || '') !== v) return false; }
    else if (k === 'textContains') { if (!(node.text || '').includes(v)) return false; }
    else if (k === 'textMatches') { if (!new RegExp(v).test(node.text || '')) return false; }
    else if (k === 'desc') { if ((node.desc || '') !== v) return false; }
    else if (k === 'descContains') { if (!(node.desc || '').includes(v)) return false; }
    else if (k === 'descMatches') { if (!new RegExp(v).test(node.desc || '')) return false; }
    else if (k === 'id') { if ((node.id || '') !== v) return false; }
    else if (k === 'idMatches') { if (!new RegExp(v).test(node.id || '')) return false; }
    else if (k === 'className') { if ((node.className || '') !== v) return false; }
    else if (k === 'classNameMatches') { if (!new RegExp(v).test(node.className || '')) return false; }
    else if (k === 'boundsInside') {
      const b = node.bounds;
      if (!(b.left >= v.left && b.top >= v.top && b.right <= v.right && b.bottom <= v.bottom)) return false;
    }
    else if (k === 'boundsContains') {
      const b = node.bounds;
      if (!(b.left <= v.left && b.top <= v.top && b.right >= v.right && b.bottom >= v.bottom)) return false;
    }
    else if (k === 'clickable') { if (!!node.clickable !== !!v) return false; }
    else if (k === 'enabled') { if (!!node.enabled !== !!v) return false; }
    else if (k === 'scrollable') { if (!!node.scrollable !== !!v) return false; }
    else return false;
  }
  return true;
}

function pinnedSelector(node) {
  return {
    className: node.className,
    ...(node.desc ? { desc: node.desc } : {}),
    boundsInside: node.bounds,
    boundsContains: node.bounds
  };
}

function uiDump(args) {
  const maxNodes = Math.min(Number(args.maxNodes || 400), 400);
  const maxDepth = Math.min(Number(args.maxDepth == null ? 32 : args.maxDepth), 32);
  const visibleOnly = args.visibleOnly !== false;
  const list = NODES.filter(x => x.depth <= maxDepth && (!visibleOnly || x.visible)).slice(0, maxNodes);
  const truncated = list.length < NODES.length;
  if ((args.format || 'text') === 'json') {
    return {
      format: 'json', window: { packageName: WINDOW.packageName, activityName: WINDOW.activityName, bounds: WINDOW.bounds },
      roots: 1, nodeCount: list.length, truncated: truncated, nodes: list
    };
  }
  const lines = ['window: ' + WINDOW.packageName + '/' + WINDOW.activityName
    + '  bounds=[0,0][1080,2376]  nodes=' + list.length + '  truncated=' + truncated];
  for (const x of list) {
    const pad = ' '.repeat(Math.max(0, x.depth) * 2);
    const pos = x.bounds ? '[' + x.bounds.left + ',' + x.bounds.top + '][' + x.bounds.right + ',' + x.bounds.bottom + ']' : 'c=(' + x.center.join(',') + ')';
    lines.push('#n' + (list.indexOf(x) + 1) + pad + x.className.split('.').pop()
      + ' ' + (x.text ? '"' + x.text + '"' : '')
      + (x.desc ? ' desc=' + x.desc : '')
      + (x.id ? ' id=' + x.id : '')
      + ' ' + pos);
  }
  return lines.join('\n');
}

function uiFind(args) {
  const sel = args.selector || {};
  if (!Object.keys(sel).length) throw new Error('selector needs at least one condition');
  const hits = NODES.filter(x => matchNode(x, sel));
  const limit = Math.min(Number(args.limit || 10), 100);
  return {
    selector: sel, count: hits.length, returned: Math.min(hits.length, limit),
    truncated: hits.length > limit, elapsedMs: 3,
    nodes: hits.slice(0, limit).map(x => ({
      className: x.className, text: x.text, desc: x.desc, id: x.id,
      bounds: x.bounds, center: x.center, clickable: x.clickable, enabled: x.enabled,
      selector: pinnedSelector(x)
    }))
  };
}

function explain(args) {
  const sel = args.selector || {};
  const steps = [];
  const acc = {};
  let failed = null;
  for (const k of Object.keys(sel)) {
    acc[k] = sel[k];
    const c = NODES.filter(x => matchNode(x, acc)).length;
    steps.push({ condition: k, count: c });
    if (!c && failed == null) failed = k;
  }
  return { selector: sel, steps, failedCondition: failed, matches: steps.length ? steps[steps.length - 1].count : 0 };
}


/* 录制模拟：一段 5 行的记录文件，每次 files_read 多给一行，模拟真机边操作边写 */
const REC = { shown: 0, startedAt: 0, events: [
  JSON.stringify({ type: 'start', t: 1, cap: { observeTouch: 'function', onTouch: 'function', observeKey: 'function' }, screen: { w: 1080, h: 2376 }, pkg: 'com.jingdong.app.mall' }),
  JSON.stringify({ type: 'tap', t: 2, x: 300, y: 352, pkg: 'com.jingdong.app.mall', node: { text: '天天领豆', desc: '', id: 'com.jingdong.app.mall:id/coupon-sign', className: 'android.widget.Button', bounds: { left: 600, top: 300, right: 1000, bottom: 420 }, counts: { id: 1, text: 1, textContains: 1, desc: -1 } } }),
  JSON.stringify({ type: 'tap', t: 3, x: 900, y: 900, pkg: 'com.jingdong.app.mall', node: { text: '立即领取', desc: '', id: '', className: 'android.widget.Button', bounds: { left: 560, top: 520, right: 1000, bottom: 620 }, counts: { id: -1, text: 2, textContains: 2, desc: -1 } } }),
  JSON.stringify({ type: 'swipe', t: 4, dur: 420, x1: 540, y1: 1600, x2: 540, y2: 600, pkg: 'com.jingdong.app.mall' }),
  JSON.stringify({ type: 'key', key: 'back', t: 5 })
]};
function callTool(name, args) {
  switch (name) {
    case 'device_ping':
      return { plugin: 'Mock MCP', pluginId: 'mock', versionName: '0.0.1', versionCode: 1, manufacturer: 'Mock', model: 'MockPhone', androidApi: 36, androidRelease: '16', time: new Date().toISOString() };
    case 'device_info':
      return { manufacturer: 'Mock', model: 'MockPhone', androidApi: 36, androidRelease: '16', versionName: '6.8.0', screen: { width: 1080, height: 2376 } };
    case 'ui_dump':
      return uiDump(args || {});
    case 'ui_find':
      return uiFind(args || {});
    case 'ui_explain_selector':
      return explain(args || {});
    case 'ui_current_window':
      return { packageName: WINDOW.packageName, activityName: WINDOW.activityName, bounds: WINDOW.bounds };
    case 'ui_click':
      return { ok: true, packageName: WINDOW.packageName };
    case 'screen_capture':
      return {
        __raw: true,
        content: [
          { type: 'image', data: TINY_PNG, mimeType: 'image/png' },
          { type: 'text', text: JSON.stringify({ format: 'png', width: 1080, height: 2376, annotated: false, source: 'mock' }) }
        ]
      };
    case 'script_run':
      if ((args || {}).name === 'a6studio-record') {
        REC.shown = 0;
        REC.startedAt = Date.now();
        return { executionId: 11, status: 'running', durationMs: 4 };
      }
      return { executionId: 'mock-1', status: 'finished', durationMs: 12, console: ['mock run'] };
    case 'script_stop':
      return { stopped: 1, executionId: Number((args || {}).executionId || 0) };
    case 'script_list':
      return { scripts: [] };
    case 'files_read': {
      const p = String((args || {}).path || '');
      if (p === 'studio-record.jsonl') {
        /* 按时间增长（而不是按读取次数）：录制期不轮询时，停止后一次读取也应拿到全部事件 */
        const elapsed = REC.startedAt ? Date.now() - REC.startedAt : 0;
        REC.shown = Math.min(REC.events.length, 1 + Math.floor(elapsed / 400));
        const shown = REC.events.slice(0, REC.shown);
        return { path: p, size: shown.join('\n').length, content: shown.join('\n'), truncated: false, complete: REC.shown >= REC.events.length };
      }
      throw new Error('file not found: ' + p);
    }
    case 'files_stat':
      return { exists: true, type: 'file', size: 4096, modified: new Date().toISOString() };
    case 'console_tail':
      return { entries: [{ level: 'info', message: 'mock console' }] };
    default:
      throw new Error('unknown tool: ' + name);
  }
}

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'POST, GET, OPTIONS',
  'access-control-allow-headers': 'content-type, accept, mcp-session-id, authorization',
  'access-control-expose-headers': 'mcp-session-id',
  'access-control-max-age': '86400'
};

function send(res, code, obj) {
  const body = obj == null ? '' : JSON.stringify(obj);
  res.writeHead(code, Object.assign({ 'content-type': 'application/json; charset=utf-8' }, CORS));
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); res.end(); return; }
  if (req.method === 'GET') { send(res, 200, { ok: true, nodes: NODES.length }); return; }
  if (req.method !== 'POST') { send(res, 405, { error: 'POST only' }); return; }

  const chunks = [];
  for await (const c of req) chunks.push(c);
  let msg;
  try { msg = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch (e) { send(res, 400, { error: 'bad json' }); return; }

  if (!msg.id) { res.writeHead(202, CORS); res.end(); return; }   // notification

  try {
    let result;
    if (msg.method === 'initialize') {
      result = { protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'mock-mcp', version: '0.0.1' } };
    } else if (msg.method === 'tools/call') {
      const data = callTool(msg.params.name, msg.params.arguments || {});
      result = (data && data.__raw)
        ? { content: data.content, isError: false }
        : { content: [{ type: 'text', text: typeof data === 'string' ? data : JSON.stringify(data) }], isError: false };
    } else if (msg.method === 'tools/list') {
      result = { tools: [] };
    } else {
      throw new Error('unknown method: ' + msg.method);
    }
    send(res, 200, { jsonrpc: '2.0', id: msg.id, result });
    console.log(new Date().toISOString(), msg.method, (msg.params && msg.params.name) || '');
  } catch (e) {
    send(res, 200, { jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: String(e.message || e) } });
    console.error('tool error:', e.message);
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('Mock MCP 已启动：http://127.0.0.1:' + PORT + '/mcp （节点 ' + NODES.length + ' 个）');
});