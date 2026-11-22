// 统一响应封装：CORS + JSON。EdgeOne Cloud Functions 运行在 Node.js 上。
const BASE_HEADERS = {
  "Content-Type": "application/json; charset=UTF-8",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Content-Type, X-HyperGlow-Device",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

export function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...BASE_HEADERS, ...extraHeaders },
  });
}

export function fail(status, code, detail) {
  const body = { error: code };
  if (detail !== undefined) body.detail = detail;
  return json(body, status);
}

export function preflight() {
  return new Response(null, { status: 204, headers: BASE_HEADERS });
}
