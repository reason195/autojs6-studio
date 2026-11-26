/**
 * AutoJs6 Studio · MCP 转发桥接
 * 作用：给浏览器补 CORS，并把请求原样转发到 AutoJs6 的 MCP Server。
 * 不改协议、不改报文，只做透明代理。
 *
 * 用法：
 *   MCP_UPSTREAM="http://192.168.1.20:9317/mcp" node mcp-bridge.mjs
 *   浏览器「真机」页填 http://127.0.0.1:8765/mcp
 *
 * 可选环境变量：
 *   PORT（默认 8765）
 *   HOST（默认 127.0.0.1；只有确实要让别的机器连进来时才改，改了等于把手机交给整个局域网）
 *   MCP_TOKEN（服务端要 Bearer 时由桥接代填；页面自己带了 Authorization 就用页面的）
 *   ALLOW_ORIGINS（额外放行的来源，逗号分隔，如 https://studio.example.com）
 *
 * 安全边界：桥接手里有能在手机上跑脚本的令牌，所以只接受本机页面的请求——
 *   - 默认只监听回环地址；
 *   - Host 必须是 127.0.0.1 / localhost / [::1]（挡 DNS rebinding）；
 *   - Origin 只放行回环地址的页面和 ALLOW_ORIGINS，其余一律 403（挡任意网页跨域调用）；
 *   - file:// 页面的 Origin 是 "null"，而任意网站的 sandbox iframe 也能伪造出 "null"，
 *     所以 "null" 来源必须自带 Authorization，桥接不会替它代填 MCP_TOKEN；
 *   - 请求体上限 1 MB。
 */
import http from 'node:http';

const PORT = Number(process.env.PORT || 8765);
const HOST = process.env.HOST || '127.0.0.1';
const UPSTREAM = process.env.MCP_UPSTREAM || '';
const TOKEN = process.env.MCP_TOKEN || '';
const MAX_BODY = 1024 * 1024;
const MAX_RESP_BYTES = 8 * 1024 * 1024;
const UPSTREAM_TIMEOUT_MS = Number(process.env.UPSTREAM_TIMEOUT_MS || 30000);
const ALLOW_NULL_ORIGIN = process.env.ALLOW_NULL_ORIGIN === '1' || process.env.ALLOW_NULL_ORIGIN === 'true';
const EXTRA_ORIGINS = new Set(
  (process.env.ALLOW_ORIGINS || '').split(',').map(s => s.trim().replace(/\/+$/, '').toLowerCase()).filter(Boolean)
);

const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);
const LOOPBACK_BIND = LOOPBACK_HOSTS.has(HOST) || HOST === '::1';

const CORS = {
  'access-control-allow-methods': 'POST, GET, OPTIONS',
  'access-control-allow-headers': 'content-type, accept, mcp-session-id, authorization',
  'access-control-expose-headers': 'mcp-session-id, content-type',
  'access-control-max-age': '86400',
  'vary': 'origin'
};

function hostnameOf(hostHeader) {
  if (!hostHeader) return '';
  const h = hostHeader.trim().toLowerCase();
  if (h.startsWith('[')) return h.slice(0, h.indexOf(']') + 1);
  return h.split(':')[0];
}

/** 'loopback' | 'null' | 'extra' | 'none'（没带 Origin，如 curl）| null（拒绝） */
function classifyOrigin(origin) {
  if (origin === undefined) return 'none';
  if (origin === 'null') return ALLOW_NULL_ORIGIN ? 'null' : null;
  if (EXTRA_ORIGINS.has(origin.trim().replace(/\/+$/, '').toLowerCase())) return 'extra';
  try {
    const u = new URL(origin);
    if ((u.protocol === 'http:' || u.protocol === 'https:') && LOOPBACK_HOSTS.has(u.hostname)) return 'loopback';
  } catch { /* fall through */ }
  return null;
}

function send(res, code, headers, body, origin, onDone) {
  const cors = origin !== undefined ? Object.assign({ 'access-control-allow-origin': origin }, CORS) : CORS;
  res.writeHead(code, Object.assign({}, cors, headers || {}));
  res.end(body, onDone);
}

function sendJson(res, code, obj, origin, onDone) {
  send(res, code, { 'content-type': 'application/json; charset=utf-8' }, JSON.stringify(obj), origin, onDone);
}

async function readBody(req) {
  const declared = Number(req.headers['content-length'] || 0);
  if (Number.isFinite(declared) && declared > MAX_BODY) return null;
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > MAX_BODY) return null;
    chunks.push(c);
  }
  return Buffer.concat(chunks);
}

async function readUpstreamCapped(up) {
  const declared = Number(up.headers.get('content-length') || 0);
  if (Number.isFinite(declared) && declared > MAX_RESP_BYTES) return null;
  if (!up.body) return Buffer.alloc(0);
  const reader = up.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_RESP_BYTES) {
        await reader.cancel();
        return null;
      }
      chunks.push(Buffer.from(value));
    }
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks);
}

const server = http.createServer(async (req, res) => {
  // 绑定回环地址时，Host 必须也是回环名：否则就是 DNS rebinding（evil.com 解析到 127.0.0.1）
  if (LOOPBACK_BIND && !LOOPBACK_HOSTS.has(hostnameOf(req.headers.host))) {
    send(res, 403, { 'content-type': 'text/plain; charset=utf-8' }, 'Host not allowed');
    return;
  }
  const origin = req.headers.origin;
  const kind = classifyOrigin(origin);
  if (kind === null) {
    send(res, 403, { 'content-type': 'text/plain; charset=utf-8' }, 'Origin not allowed');
    console.warn(new Date().toISOString(), '拒绝来源', origin);
    return;
  }

  if (req.method === 'OPTIONS') { send(res, 204, {}, '', origin); return; }

  if (req.method === 'GET' && (req.url === '/health' || req.url === '/')) {
    const info = kind === 'null' ? { ok: true, port: PORT } : { ok: true, upstream: UPSTREAM || null, port: PORT };
    sendJson(res, 200, info, origin);
    return;
  }

  if (req.method !== 'POST') { send(res, 405, { 'content-type': 'text/plain' }, 'POST only', origin); return; }
  const ct = String(req.headers['content-type'] || '').trim().toLowerCase();
  if (!ct.startsWith('application/json')) {
    sendJson(res, 415, { error: '仅接受 Content-Type: application/json' }, origin);
    return;
  }
  if (!UPSTREAM) {
    sendJson(res, 500, { error: '未设置 MCP_UPSTREAM，例如 MCP_UPSTREAM=http://192.168.1.20:9317/mcp' }, origin);
    return;
  }

  const body = await readBody(req);
  if (body === null) {
    send(res, 413, { 'content-type': 'application/json; charset=utf-8', 'connection': 'close' }, JSON.stringify({ error: '请求体超过 1 MB' }), origin, () => {
      res.socket?.end();
    });
    return;
  }

  const headers = {
    'content-type': req.headers['content-type'] || 'application/json',
    'accept': req.headers['accept'] || 'application/json, text/event-stream'
  };
  if (req.headers['mcp-session-id']) headers['mcp-session-id'] = req.headers['mcp-session-id'];
  if (req.headers['authorization']) headers['authorization'] = req.headers['authorization'];
  else if (TOKEN && kind !== 'null') headers['authorization'] = 'Bearer ' + TOKEN;

  try {
    const up = await fetch(UPSTREAM, {
      method: 'POST',
      headers,
      body,
      redirect: 'error',
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS)
    });
    const buf = await readUpstreamCapped(up);
    if (buf === null) {
      sendJson(res, 502, { error: '上游响应超过 8 MB 上限' }, origin);
      return;
    }
    const out = {
      'content-type': up.headers.get('content-type') || 'application/json'
    };
    const sid = up.headers.get('mcp-session-id');
    if (sid) out['mcp-session-id'] = sid;
    send(res, up.status, out, buf, origin);
    console.log(new Date().toISOString(), 'POST', req.url, '→', up.status, buf.length + 'B');
  } catch (e) {
    sendJson(res, 502, { error: String(e.message || e) }, origin);
    console.error('转发失败：', e.message);
  }
});

server.listen(PORT, HOST, () => {
  const shown = HOST.includes(':') ? '[' + HOST + ']' : HOST;
  console.log('AutoJs6 Studio 桥接已启动：http://' + shown + ':' + PORT + '/mcp');
  console.log('上游：' + (UPSTREAM || '（未配置，先设置 MCP_UPSTREAM）'));
  if (!LOOPBACK_BIND) console.warn('注意：HOST=' + HOST + ' 不是回环地址，局域网内的机器都能连到这个桥接');
});
