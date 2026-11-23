// HyperGlow 配置预设匿名 Hub（Cloudflare Worker）
//
// 作用：让 App 用户**不需要 GitHub Token** 也能上传预设。Worker 在服务端持有
// GitHub Token（secret），校验匿名请求后直接更新仓库的 `presets` Release 资产。
// 读取仍然可以直接走 GitHub（公开 Release），这个 Worker 只处理上传。
//
// 安全边界：
// - 只接受配置备份信封（format/version 固定）；分节不做固定白名单，未知顶层键原样保留，
//   这样后续版本新增的设置面无需先改 Hub 就能上传。插件设置由 App 默认不勾选，
//   只有用户明确选择才会进入公开清单。
// - 单条 256 KiB、清单 8 MiB / 100 条。
// - 每 IP 每小时 10 次（需要绑定 KV；未绑定则跳过限流，见 README）。
// - 匿名上传用 `X-HyperGlow-Device` 的哈希做归属：只有同一设备能覆盖自己上传的条目，
//   不能凭 id 劫持/覆盖别人。
// - Token 只存在 Worker secret 里；响应与日志都不包含它。

const DEFAULT_REPO = "aodianjun/com.aodianjun.hyperglow.cnplus";
const DEFAULT_TAG = "presets";
const ASSET_NAME = "presets.json";
const MANIFEST_FORMAT = "hyperglow-preset-hub";
const MANIFEST_VERSION = 1;
const BACKUP_FORMAT = "hyperglow-config-backup";
const BACKUP_VERSION = 1;

// 分节名只做形状约束（同时挡掉 __proto__ 之类的键），不维护已知键白名单。
const SECTION_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/;
const MAX_SECTIONS = 64;

const MAX_PRESET_BYTES = 256 * 1024;
const MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
const MAX_NAME = 20;
const MAX_NOTE = 60;
const MAX_AUTHOR = 20;
const MAX_PRESETS = 100;
const RATE_LIMIT_PER_HOUR = 10;

const GITHUB_API = "https://api.github.com";
const GITHUB_UPLOADS = "https://uploads.github.com";

export default {
  async fetch(request, env) {
    return cors(await route(request, env));
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return new Response(null, { status: 204 });
  if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
    return json({
      ok: true,
      repo: repoOf(env),
      tag: tagOf(env),
      upload: "POST /api/presets",
      delete: "POST /api/presets/delete",
      list: "GET /api/presets",
    });
  }
  if (url.pathname === "/api/presets" && request.method === "GET") {
    return listManifest(env);
  }
  if (url.pathname === "/api/presets" && request.method === "POST") {
    return uploadPreset(request, env);
  }
  if (url.pathname === "/api/presets/delete" && request.method === "POST") {
    return deletePreset(request, env);
  }
  return json({ error: "not_found" }, 404);
}

async function listManifest(env) {
  const gh = github(env);
  const release = await getRelease(gh);
  if (!release) return json({ format: MANIFEST_FORMAT, version: MANIFEST_VERSION, presets: [] });
  const asset = release.assets.find((item) => item.name === ASSET_NAME);
  if (!asset) return json({ format: MANIFEST_FORMAT, version: MANIFEST_VERSION, presets: [] });
  const raw = await downloadAsset(gh, asset.id);
  return new Response(raw, {
    status: 200,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

async function uploadPreset(request, env) {
  if (!env.GITHUB_TOKEN) return json({ error: "server_not_configured" }, 500);
  if (await rateLimited(request, env)) return json({ error: "rate_limited" }, 429);

  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (declaredLength > MAX_PRESET_BYTES + 8192) return json({ error: "too_large" }, 413);
  const rawBody = await request.text();
  if (byteLength(rawBody) > MAX_PRESET_BYTES + 8192) {
    return json({ error: "too_large" }, 413);
  }

  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const device = (request.headers.get("X-HyperGlow-Device") || "").trim();
  if (!device || device.length > 128) return json({ error: "missing_device" }, 400);

  const preset = normalizePreset(body);
  if (!preset) return json({ error: "invalid_preset" }, 400);

  try {
    const owner = await ownerHash(device, env.OWNER_SALT || "");
    const manifest = await updateManifest(env, preset, owner);
    return json({ ok: true, id: preset.id, updatedAt: manifest.updatedAt });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 502;
    const code = error instanceof HttpError ? error.code : "github_failed";
    console.error(`upload rejected: ${code} (${status})`);
    return json({ error: code }, status);
  }
}

function normalizePreset(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  const name = cleanText(body.name, MAX_NAME);
  if (!name) return null;
  const note = cleanText(body.note, MAX_NOTE);
  const author = cleanText(body.author, MAX_AUTHOR) || "anonymous";
  const payload = body.payload;
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  if (payload.format !== BACKUP_FORMAT || payload.version !== BACKUP_VERSION) return null;

  const filtered = { format: BACKUP_FORMAT, version: BACKUP_VERSION };
  let sectionCount = 0;
  for (const [key, value] of Object.entries(payload)) {
    if (key === "format" || key === "version") continue;
    // 任意合法顶层分节都原样保留（前向兼容后续版本新增的设置）。
    if (!SECTION_KEY_PATTERN.test(key)) return null;
    if (value === null || typeof value === "undefined") return null;
    if (++sectionCount > MAX_SECTIONS) return null;
    filtered[key] = value;
  }
  if (sectionCount === 0) return null;
  if (byteLength(JSON.stringify(filtered)) > MAX_PRESET_BYTES) return null;

  const id = typeof body.id === "string" && /^[A-Za-z0-9._-]{1,80}$/.test(body.id)
    ? body.id
    : crypto.randomUUID();
  return { id, name, note, author, payload: filtered };
}

/**
 * 删除自己上传的条目。归属校验与上传同一套 owner 哈希：没有 owner（Token 上传/精选）
 * 或 owner 不匹配都返回 403，只有原上传设备能删。
 */
async function deletePreset(request, env) {
  if (!env.GITHUB_TOKEN) return json({ error: "server_not_configured" }, 500);
  if (await rateLimited(request, env)) return json({ error: "rate_limited" }, 429);

  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (declaredLength > 4096) return json({ error: "too_large" }, 413);
  const rawBody = await request.text();
  if (byteLength(rawBody) > 4096) return json({ error: "too_large" }, 413);

  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  const id = typeof body?.id === "string" ? body.id.trim() : "";
  if (!/^[A-Za-z0-9._-]{1,80}$/.test(id)) return json({ error: "invalid_id" }, 400);

  const device = (request.headers.get("X-HyperGlow-Device") || "").trim();
  if (!device || device.length > 128) return json({ error: "missing_device" }, 400);

  try {
    const owner = await ownerHash(device, env.OWNER_SALT || "");
    const gh = github(env);
    const release = await getRelease(gh);
    if (!release) return json({ error: "not_found" }, 404);
    const asset = release.assets.find((item) => item.name === ASSET_NAME);
    if (!asset) return json({ error: "not_found" }, 404);

    const manifest = parseManifest(await downloadAsset(gh, asset.id));
    if (!manifest) throw new HttpError(502, "bad_manifest");
    const existing = manifest.presets.find((item) => item.id === id);
    if (!existing) return json({ error: "not_found" }, 404);
    if (!existing.owner || existing.owner !== owner) {
      return json({ error: "not_owner" }, 403);
    }

    const next = {
      format: MANIFEST_FORMAT,
      version: MANIFEST_VERSION,
      updatedAt: Date.now(),
      presets: manifest.presets.filter((item) => item.id !== id),
    };
    const nextJson = JSON.stringify(next);
    if (byteLength(nextJson) > MAX_MANIFEST_BYTES) {
      throw new HttpError(413, "manifest_too_large");
    }
    if (env.RATE_LIMIT) {
      await env.RATE_LIMIT.put("manifest_backup", nextJson, { expirationTtl: 30 * 86_400 });
    }
    await deleteAsset(gh, asset.id);
    await uploadAsset(gh, release.uploadUrl, nextJson);
    return json({ ok: true, id });
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 502;
    const code = error instanceof HttpError ? error.code : "github_failed";
    console.error(`delete rejected: ${code} (${status})`);
    return json({ error: code }, status);
  }
}

async function updateManifest(env, preset, owner) {
  const gh = github(env);
  let release = await getRelease(gh);
  if (!release) release = await createRelease(gh);

  const asset = release.assets.find((item) => item.name === ASSET_NAME);
  let manifest = emptyManifest();
  if (asset) {
    const raw = await downloadAsset(gh, asset.id);
    manifest = parseManifest(raw);
    if (!manifest) throw new HttpError(502, "bad_manifest");
  } else if (env.RATE_LIMIT) {
    // 资产不存在时先从 KV 备份恢复：删除旧资产后上传失败会留下这个洞。
    const backup = await env.RATE_LIMIT.get("manifest_backup");
    if (backup) {
      const recovered = parseManifest(backup);
      if (recovered) manifest = recovered;
    }
  }

  const now = Date.now();
  const existing = manifest.presets.find((item) => item.id === preset.id);
  if (existing) {
    // Token 上传的条目没有 owner：匿名通道不允许认领/覆盖它。
    if (!existing.owner || existing.owner !== owner) {
      throw new HttpError(409, "id_taken");
    }
  }

  const entry = {
    id: preset.id,
    name: preset.name,
    note: preset.note,
    author: preset.author || existing?.author || "anonymous",
    createdAt: existing?.createdAt || now,
    updatedAt: now,
    owner: existing?.owner || owner,
    payload: preset.payload,
  };
  const presets = [entry, ...manifest.presets.filter((item) => item.id !== preset.id)]
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .slice(0, MAX_PRESETS);
  const next = {
    format: MANIFEST_FORMAT,
    version: MANIFEST_VERSION,
    updatedAt: now,
    presets,
  };
  const nextJson = JSON.stringify(next);
  if (byteLength(nextJson) > MAX_MANIFEST_BYTES) {
    throw new HttpError(413, "manifest_too_large");
  }

  // 删除旧资产前先备份整份清单：上传失败的下一次请求可从 KV 恢复。
  if (env.RATE_LIMIT) {
    await env.RATE_LIMIT.put("manifest_backup", nextJson, { expirationTtl: 30 * 86_400 });
  }
  if (asset) await deleteAsset(gh, asset.id);
  await uploadAsset(gh, release.uploadUrl, nextJson);
  return next;
}

function parseManifest(raw) {
  let root;
  try {
    root = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!root || root.format !== MANIFEST_FORMAT || root.version !== MANIFEST_VERSION) {
    return null;
  }
  if (!Array.isArray(root.presets)) return null;
  const presets = root.presets
    .filter((item) => item && typeof item.id === "string" && item.id &&
      typeof item.name === "string" && item.name && item.payload &&
      typeof item.payload === "object" &&
      byteLength(JSON.stringify(item.payload)) <= MAX_PRESET_BYTES)
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .slice(0, MAX_PRESETS);
  return {
    format: MANIFEST_FORMAT,
    version: MANIFEST_VERSION,
    updatedAt: Number(root.updatedAt) || 0,
    presets,
  };
}

function emptyManifest() {
  return { format: MANIFEST_FORMAT, version: MANIFEST_VERSION, updatedAt: 0, presets: [] };
}

async function getRelease(gh) {
  const response = await fetch(
    `${GITHUB_API}/repos/${gh.repo}/releases/tags/${encodeURIComponent(gh.tag)}`,
    { headers: githubHeaders(gh) },
  );
  if (response.status === 404) return null;
  if (!response.ok) throw new HttpError(502, `github_${response.status}`);
  return releaseFromJson(await response.json());
}

async function createRelease(gh) {
  const response = await fetch(`${GITHUB_API}/repos/${gh.repo}/releases`, {
    method: "POST",
    headers: { ...githubHeaders(gh), "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({
      tag_name: gh.tag,
      name: "HyperGlow preset hub",
      body: "HyperGlow 配置预设云端通道（presets.json）。该 Release 刻意标记为预发布，避免被应用更新检查误判为最新版本。",
      draft: false,
      prerelease: true,
    }),
  });
  if (response.status === 201) return releaseFromJson(await response.json());
  if (response.status === 422) {
    const existing = await getRelease(gh);
    if (existing) return existing;
  }
  throw new HttpError(502, `github_${response.status}`);
}

function releaseFromJson(root) {
  const assets = Array.isArray(root.assets)
    ? root.assets
        .filter((item) => item && item.id && typeof item.name === "string")
        .map((item) => ({ id: item.id, name: item.name }))
    : [];
  return { id: root.id, uploadUrl: root.upload_url, assets };
}

async function downloadAsset(gh, assetId) {
  const apiUrl = `${GITHUB_API}/repos/${gh.repo}/releases/assets/${assetId}`;
  const first = await fetch(apiUrl, {
    headers: { ...githubHeaders(gh), Accept: "application/octet-stream" },
    redirect: "manual",
  });
  let response = first;
  if (first.status >= 300 && first.status < 400) {
    const location = first.headers.get("Location");
    if (!location) throw new HttpError(502, "asset_redirect_missing");
    // 签名 CDN 地址不再带 Token，避免把服务端凭据转给第三方主机。
    response = await fetch(location, { headers: { Accept: "application/octet-stream" } });
  }
  if (!response.ok) throw new HttpError(502, `asset_${response.status}`);
  const declared = Number(response.headers.get("content-length") || "0");
  if (declared > MAX_MANIFEST_BYTES) throw new HttpError(413, "manifest_too_large");
  const text = await response.text();
  if (byteLength(text) > MAX_MANIFEST_BYTES) {
    throw new HttpError(413, "manifest_too_large");
  }
  return text;
}

async function uploadAsset(gh, uploadUrl, manifestJson) {
  const base = uploadUrl.split("{")[0];
  const url = `${base}?name=${encodeURIComponent(ASSET_NAME)}`;
  const response = await fetch(url, {
    method: "POST",
    headers: { ...githubHeaders(gh), "Content-Type": "application/json; charset=utf-8" },
    body: manifestJson,
  });
  if (response.status === 201) return;
  // 请求可能成功但响应丢失：回查一次同名资产。
  const release = await getRelease(gh);
  if (release && release.assets.some((item) => item.name === ASSET_NAME)) return;
  throw new HttpError(502, `upload_${response.status}`);
}

async function deleteAsset(gh, assetId) {
  const response = await fetch(
    `${GITHUB_API}/repos/${gh.repo}/releases/assets/${assetId}`,
    { method: "DELETE", headers: githubHeaders(gh) },
  );
  if (response.ok || response.status === 404) return;
  throw new HttpError(502, `delete_${response.status}`);
}

function githubHeaders(gh) {
  return {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${gh.token}`,
    "User-Agent": "HyperGlow-Preset-Hub",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

function github(env) {
  return {
    repo: repoOf(env),
    tag: tagOf(env),
    token: env.GITHUB_TOKEN,
  };
}

function repoOf(env) {
  return (env.GITHUB_REPO || DEFAULT_REPO).trim();
}

function tagOf(env) {
  return (env.GITHUB_TAG || DEFAULT_TAG).trim();
}

async function rateLimited(request, env) {
  if (!env.RATE_LIMIT) return false;
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const bucket = Math.floor(Date.now() / 3_600_000);
  const key = `rl:${ip}:${bucket}`;
  const current = Number((await env.RATE_LIMIT.get(key)) || "0");
  if (current >= RATE_LIMIT_PER_HOUR) return true;
  await env.RATE_LIMIT.put(key, String(current + 1), { expirationTtl: 3_600 });
  return false;
}

async function ownerHash(device, salt) {
  const data = new TextEncoder().encode(`${device}${salt}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

function cleanText(value, maxLength) {
  if (typeof value !== "string") return "";
  return value
    .replace(/[\u0000-\u001F\u007F]/g, " ")
    .trim()
    .slice(0, maxLength);
}

function byteLength(text) {
  return new TextEncoder().encode(text).length;
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function cors(response) {
  const headers = new Headers(response.headers);
  headers.set("Access-Control-Allow-Origin", "*");
  headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  headers.set("Access-Control-Allow-Headers", "Content-Type, X-HyperGlow-Device");
  headers.set("Access-Control-Max-Age", "86400");
  return new Response(response.body, { status: response.status, headers });
}

class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}
