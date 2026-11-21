/**
 * AutoJs6 Studio · MCP 转发桥接
 * 作用：给浏览器补 CORS，并把请求原样转发到 AutoJs6 的 MCP Server。
 * 不改协议、不改报文，只做透明代理。
 *
 * 用法：
 *   MCP_UPSTREAM="http://192.168.1.20:9317/mcp" node mcp-bridge.mjs
 *   浏览器「真机」页填 http://127.0.0.1:8765/mcp
 *
 * 可选环境变量：PORT（默认 8765）、MCP_TOKEN（如果服务端要 Bearer）。
 */
import http from 'node:http';

const PORT = Number(process.env.PORT || 8765);
const UPSTREAM = process.env.MCP_UPSTREAM || '';
const TOKEN = process.env.MCP_TOKEN || '';

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'POST, GET, OPTIONS',
  'access-control-allow-headers': 'content-type, accept, mcp-session-id, authorization',
  'access-control-expose-headers': 'mcp-session-id, content-type',
  'access-control-max-age': '86400'
};

function send(res, code, headers, body) {
  res.writeHead(code, Object.assign({}, CORS, headers || {}));
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') { send(res, 204, {}, ''); return; }

  if (req.method === 'GET' && (req.url === '/health' || req.url === '/')) {
    send(res, 200, { 'content-type': 'application/json; charset=utf-8' },
      JSON.stringify({ ok: true, upstream: UPSTREAM || null, port: PORT }));
    return;
  }

  if (req.method !== 'POST') { send(res, 405, { 'content-type': 'text/plain' }, 'POST only'); return; }
  if (!UPSTREAM) {
    send(res, 500, { 'content-type': 'application/json; charset=utf-8' },
      JSON.stringify({ error: '未设置 MCP_UPSTREAM，例如 MCP_UPSTREAM=http://192.168.1.20:9317/mcp' }));
    return;
  }

  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = Buffer.concat(chunks);

  const headers = {
    'content-type': req.headers['content-type'] || 'application/json',
    'accept': req.headers['accept'] || 'application/json, text/event-stream'
  };
  if (req.headers['mcp-session-id']) headers['mcp-session-id'] = req.headers['mcp-session-id'];
  if (TOKEN) headers['authorization'] = 'Bearer ' + TOKEN;

  try {
    const up = await fetch(UPSTREAM, { method: 'POST', headers, body });
    const buf = Buffer.from(await up.arrayBuffer());
    const out = {
      'content-type': up.headers.get('content-type') || 'application/json'
    };
    const sid = up.headers.get('mcp-session-id');
    if (sid) out['mcp-session-id'] = sid;
    send(res, up.status, out, buf);
    console.log(new Date().toISOString(), 'POST', req.url, '→', up.status, buf.length + 'B');
  } catch (e) {
    send(res, 502, { 'content-type': 'application/json; charset=utf-8' },
      JSON.stringify({ error: String(e.message || e) }));
    console.error('转发失败：', e.message);
  }
});

server.listen(PORT, () => {
  console.log('AutoJs6 Studio 桥接已启动：http://127.0.0.1:' + PORT + '/mcp');
  console.log('上游：' + (UPSTREAM || '（未配置，先设置 MCP_UPSTREAM）'));
});