// 匿名 Hub 的无网络冒烟测试：mock GitHub API，跑真实的 Worker 路由/校验/合并逻辑。
// 运行：cd tools/preset-hub-worker && node test.mjs
import assert from "node:assert/strict";
import worker from "./worker.js";

let manifestText = null;
let releaseCreated = false;
let rateBlocked = false;
const kv = new Map();

const releaseJson = () => ({
  id: 1,
  upload_url: "https://uploads.test/repos/o/r/releases/1/assets{?name,label}",
  assets: manifestText ? [{ id: 1, name: "presets.json" }] : [],
});

globalThis.fetch = async (url, init = {}) => {
  const target = String(url);
  const method = (init.method || "GET").toUpperCase();

  if (target.includes("/releases/tags/presets") && method === "GET") {
    return releaseCreated
      ? json(releaseJson())
      : new Response("{}", { status: 404 });
  }
  if (target.endsWith("/releases") && method === "POST") {
    releaseCreated = true;
    return json(releaseJson(), 201);
  }
  if (target.includes("/releases/assets/1") && method === "GET") {
    return manifestText
      ? new Response(null, {
          status: 302,
          headers: { Location: "https://cdn.test/presets.json" },
        })
      : new Response("{}", { status: 404 });
  }
  if (target === "https://cdn.test/presets.json" && method === "GET") {
    return new Response(manifestText || "", { status: 200 });
  }
  if (target.includes("/releases/assets/1") && method === "DELETE") {
    manifestText = null;
    return new Response(null, { status: 204 });
  }
  if (target.startsWith("https://uploads.test/") && method === "POST") {
    manifestText = String(init.body || "");
    return json({ id: 1, name: "presets.json" }, 201);
  }
  if (target.endsWith("/api/health") || target.endsWith("/health")) {
    return json({ ok: true });
  }
  throw new Error(`unexpected fetch: ${method} ${target}`);
};

const env = {
  GITHUB_TOKEN: "test-token",
  GITHUB_REPO: "o/r",
  GITHUB_TAG: "presets",
  RATE_LIMIT: {
    get: async (key) => (rateBlocked ? "10" : (kv.get(key) ?? null)),
    put: async (key, value) => kv.set(key, value),
  },
};

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function request(device, body, method = "POST") {
  return new Request("https://hub.test/api/presets", {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-HyperGlow-Device": device,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

function preset(id, name = "Night", extra = {}) {
  return {
    id,
    name,
    note: "",
    author: "tester",
    payload: {
      format: "hyperglow-config-backup",
      version: 1,
      customization: { id: "doc" },
      ...extra,
    },
  };
}

async function upload(device, body) {
  const response = await worker.fetch(request(device, body), env);
  const text = await response.text();
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    // ignore
  }
  return { status: response.status, body: parsed };
}

function currentManifest() {
  return JSON.parse(manifestText);
}

// 1. 首次上传：创建 prerelease + 资产
let result = await upload("device-A", preset("preset-1"));
assert.equal(result.status, 200);
assert.equal(result.body.ok, true);
assert.equal(releaseCreated, true);
let manifest = currentManifest();
assert.equal(manifest.presets.length, 1);
assert.equal(manifest.presets[0].id, "preset-1");
assert.equal(manifest.presets[0].owner.length, 64);

// 2. 同设备同 id：允许更新，owner 不变
const owner = manifest.presets[0].owner;
result = await upload("device-A", preset("preset-1", "Night v2"));
assert.equal(result.status, 200);
manifest = currentManifest();
assert.equal(manifest.presets.length, 1);
assert.equal(manifest.presets[0].name, "Night v2");
assert.equal(manifest.presets[0].owner, owner);

// 3. 换设备抢同 id：409
result = await upload("device-B", preset("preset-1", "Hijack"));
assert.equal(result.status, 409);
assert.equal(result.body.error, "id_taken");

// 4. 插件设置 / 未知分节：400
result = await upload(
  "device-A",
  preset("preset-2", "Leak", { pluginSettings: { demo: { apiKey: "secret" } } }),
);
assert.equal(result.status, 400);
result = await upload("device-A", preset("preset-3", "Unknown", { unknownSection: {} }));
assert.equal(result.status, 400);

// 5. 新预设新增；列表接口返回最新清单
result = await upload("device-A", preset("preset-4", "Fresh"));
assert.equal(result.status, 200);
const listResponse = await worker.fetch(new Request("https://hub.test/api/presets"), env);
assert.equal(listResponse.status, 200);
const listed = await listResponse.json();
assert.equal(listed.presets.length, 2);
assert.ok(listed.presets.some((item) => item.id === "preset-4"));

// 6. 健康检查
const health = await worker.fetch(new Request("https://hub.test/health"), env);
assert.equal(health.status, 200);

// 7. 限流
rateBlocked = true;
result = await upload("device-A", preset("preset-5", "Limited"));
assert.equal(result.status, 429);
assert.equal(result.body.error, "rate_limited");

// 8. CORS 预检
const options = await worker.fetch(
  new Request("https://hub.test/api/presets", { method: "OPTIONS" }),
  env,
);
assert.equal(options.status, 204);
assert.equal(options.headers.get("Access-Control-Allow-Origin"), "*");

console.log("worker smoke tests passed");
