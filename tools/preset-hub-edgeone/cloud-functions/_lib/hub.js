// HyperGlow 配置预设 Hub 的 EdgeOne 版实现。
//
// 与 Cloudflare Worker 版共用同一套协议与 GitHub Release：
//   GET  /api/presets  读公开清单（无需 Token）
//   POST /api/presets  匿名上传/更新（服务端 GITHUB_TOKEN 写 Release）
//
// 存储不在 EdgeOne Blob，而是直接改 GitHub 的 `presets` Release 资产，保证两个入口
// 看到的是同一份 presets.json；EdgeOne 只是备用边缘与备用凭证通道。
import { createHash, randomUUID } from "node:crypto";

const DEFAULT_REPO = "aodianjun/com.aodianjun.hyperglow.cnplus";
const DEFAULT_TAG = "presets";
const ASSET_NAME = "presets.json";
const MANIFEST_FORMAT = "hyperglow-preset-hub";
const MANIFEST_VERSION = 1;
const BACKUP_FORMAT = "hyperglow-config-backup";
const BACKUP_VERSION = 1;

// 与 Cloudflare Worker 完全一致：分节不做白名单，未知顶层键原样保留（前向兼容）。
const SECTION_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/;
const MAX_SECTIONS = 64;

const MAX_PRESET_BYTES = 256 * 1024;
const MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
const MAX_NAME = 20;
const MAX_NOTE = 60;
const MAX_AUTHOR = 20;
const MAX_PRESETS = 100;

const GITHUB_API = "https://api.github.com";

export class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

export function configFromEnv(env = {}) {
  return {
    repo: String(env.GITHUB_REPO || DEFAULT_REPO).trim(),
    tag: String(env.GITHUB_TAG || DEFAULT_TAG).trim(),
    token: String(env.GITHUB_TOKEN || "").trim(),
    // 两个通道必须用同一个盐，owner 哈希才能互相识别。
    salt: String(env.OWNER_SALT || "").trim(),
  };
}

export function emptyManifest() {
  return { format: MANIFEST_FORMAT, version: MANIFEST_VERSION, presets: [] };
}

export async function listManifest(env) {
  const config = configFromEnv(env);
  const release = await getRelease(config);
  if (!release) return emptyManifest();
  const asset = release.assets.find((item) => item.name === ASSET_NAME);
  if (!asset) return emptyManifest();
  const raw = await downloadAsset(config, asset.id);
  const manifest = parseManifest(raw);
  if (!manifest) throw new HttpError(502, "bad_manifest");
  return manifest;
}

export async function uploadPreset(env, request) {
  const config = configFromEnv(env);
  if (!config.token) throw new HttpError(500, "server_not_configured");

  const device = String(request.headers.get("x-hyperglow-device") || "").trim();
  if (!device || device.length > 128) throw new HttpError(400, "missing_device");

  const declared = Number(request.headers.get("content-length") || "0");
  if (declared && declared > MAX_PRESET_BYTES + 8192) {
    throw new HttpError(413, "too_large");
  }
  const rawBody = await request.text();
  if (Buffer.byteLength(rawBody, "utf8") > MAX_PRESET_BYTES + 8192) {
    throw new HttpError(413, "too_large");
  }

  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    throw new HttpError(400, "invalid_json");
  }

  const preset = normalizePreset(body);
  if (!preset) throw new HttpError(400, "invalid_preset");

  const owner = ownerHash(device, config.salt);
  let release = await getRelease(config);
  if (!release) release = await createRelease(config);

  const asset = release.assets.find((item) => item.name === ASSET_NAME);
  let current = emptyManifest();
  if (asset) {
    const raw = await downloadAsset(config, asset.id);
    current = parseManifest(raw);
    if (!current) throw new HttpError(502, "bad_manifest");
  }

  const now = Date.now();
  const existing = current.presets.find((item) => item.id === preset.id);
  if (existing && (!existing.owner || existing.owner !== owner)) {
    throw new HttpError(409, "id_taken");
  }

  const entry = {
    id: preset.id,
    name: preset.name,
    note: preset.note,
    author: preset.author,
    createdAt: existing?.createdAt || now,
    updatedAt: now,
    owner: existing?.owner || owner,
    payload: preset.payload,
  };
  const next = {
    format: MANIFEST_FORMAT,
    version: MANIFEST_VERSION,
    updatedAt: now,
    presets: [entry, ...current.presets.filter((item) => item.id !== preset.id)]
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
      .slice(0, MAX_PRESETS),
  };
  const nextJson = JSON.stringify(next);
  if (Buffer.byteLength(nextJson, "utf8") > MAX_MANIFEST_BYTES) {
    throw new HttpError(413, "manifest_too_large");
  }

  if (asset) await deleteAsset(config, asset.id);
  try {
    await uploadAsset(config, release.uploadUrl, nextJson);
  } catch (error) {
    // 上传请求可能已成功但响应丢失：回查一次同名资产。
    const refreshed = await getRelease(config).catch(() => null);
    if (!refreshed || !refreshed.assets.some((item) => item.name === ASSET_NAME)) {
      throw error;
    }
  }
  return { ok: true, id: preset.id, updatedAt: next.updatedAt };
}

/** 删除自己上传的条目；owner 校验与上传一致，只有原上传设备能删。 */
export async function deletePreset(env, request) {
  const config = configFromEnv(env);
  if (!config.token) throw new HttpError(500, "server_not_configured");

  const device = String(request.headers.get("x-hyperglow-device") || "").trim();
  if (!device || device.length > 128) throw new HttpError(400, "missing_device");

  const declared = Number(request.headers.get("content-length") || "0");
  if (declared > 4096) throw new HttpError(413, "too_large");
  const rawBody = await request.text();
  if (Buffer.byteLength(rawBody, "utf8") > 4096) throw new HttpError(413, "too_large");

  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    throw new HttpError(400, "invalid_json");
  }
  const id = typeof body?.id === "string" ? body.id.trim() : "";
  if (!/^[A-Za-z0-9._-]{1,80}$/.test(id)) throw new HttpError(400, "invalid_id");

  const owner = ownerHash(device, config.salt);
  const release = await getRelease(config);
  if (!release) throw new HttpError(404, "not_found");
  const asset = release.assets.find((item) => item.name === ASSET_NAME);
  if (!asset) throw new HttpError(404, "not_found");

  const current = parseManifest(await downloadAsset(config, asset.id));
  if (!current) throw new HttpError(502, "bad_manifest");
  const existing = current.presets.find((item) => item.id === id);
  if (!existing) throw new HttpError(404, "not_found");
  if (!existing.owner || existing.owner !== owner) {
    throw new HttpError(403, "not_owner");
  }

  const next = {
    format: MANIFEST_FORMAT,
    version: MANIFEST_VERSION,
    updatedAt: Date.now(),
    presets: current.presets.filter((item) => item.id !== id),
  };
  const nextJson = JSON.stringify(next);
  if (Buffer.byteLength(nextJson, "utf8") > MAX_MANIFEST_BYTES) {
    throw new HttpError(413, "manifest_too_large");
  }

  await deleteAsset(config, asset.id);
  try {
    await uploadAsset(config, release.uploadUrl, nextJson);
  } catch (error) {
    const refreshed = await getRelease(config).catch(() => null);
    if (!refreshed || !refreshed.assets.some((item) => item.name === ASSET_NAME)) {
      throw error;
    }
  }
  return { ok: true, id };
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
    if (!SECTION_KEY_PATTERN.test(key)) return null;
    if (value === null || typeof value === "undefined") return null;
    if (++sectionCount > MAX_SECTIONS) return null;
    filtered[key] = value;
  }
  if (sectionCount === 0) return null;
  if (Buffer.byteLength(JSON.stringify(filtered), "utf8") > MAX_PRESET_BYTES) return null;

  const id = typeof body.id === "string" && /^[A-Za-z0-9._-]{1,80}$/.test(body.id)
    ? body.id
    : randomUUID();
  return { id, name, note, author, payload: filtered };
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
      Buffer.byteLength(JSON.stringify(item.payload), "utf8") <= MAX_PRESET_BYTES)
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .slice(0, MAX_PRESETS);
  return {
    format: MANIFEST_FORMAT,
    version: MANIFEST_VERSION,
    updatedAt: Number(root.updatedAt) || 0,
    presets,
  };
}

function ownerHash(device, salt) {
  return createHash("sha256").update(`${device}${salt}`).digest("hex");
}

function cleanText(value, maxLength) {
  if (typeof value !== "string") return "";
  return value
    .replace(/[\u0000-\u001F\u007F]/g, " ")
    .trim()
    .slice(0, maxLength);
}

async function getRelease(config) {
  const response = await fetch(
    `${GITHUB_API}/repos/${config.repo}/releases/tags/${encodeURIComponent(config.tag)}`,
    { headers: githubHeaders(config) },
  );
  if (response.status === 404) return null;
  if (!response.ok) throw new HttpError(502, `github_${response.status}`);
  return releaseFromJson(await response.json());
}

async function createRelease(config) {
  const response = await fetch(`${GITHUB_API}/repos/${config.repo}/releases`, {
    method: "POST",
    headers: { ...githubHeaders(config), "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({
      tag_name: config.tag,
      name: "HyperGlow preset hub",
      body: "HyperGlow 配置预设云端通道（presets.json）。该 Release 刻意标记为预发布，避免被应用更新检查误判为最新版本。",
      draft: false,
      prerelease: true,
    }),
  });
  if (response.status === 201) return releaseFromJson(await response.json());
  if (response.status === 422) {
    const existing = await getRelease(config);
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

async function downloadAsset(config, assetId) {
  const apiUrl = `${GITHUB_API}/repos/${config.repo}/releases/assets/${assetId}`;
  const first = await fetch(apiUrl, {
    headers: { ...githubHeaders(config), Accept: "application/octet-stream" },
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
  const text = await response.text();
  if (Buffer.byteLength(text, "utf8") > MAX_MANIFEST_BYTES) {
    throw new HttpError(413, "manifest_too_large");
  }
  return text;
}

async function uploadAsset(config, uploadUrl, manifestJson) {
  const base = uploadUrl.split("{")[0];
  const url = `${base}?name=${encodeURIComponent(ASSET_NAME)}`;
  const response = await fetch(url, {
    method: "POST",
    headers: { ...githubHeaders(config), "Content-Type": "application/json; charset=utf-8" },
    body: manifestJson,
  });
  if (response.status === 201) return;
  const release = await getRelease(config);
  if (release && release.assets.some((item) => item.name === ASSET_NAME)) return;
  throw new HttpError(502, `upload_${response.status}`);
}

async function deleteAsset(config, assetId) {
  const response = await fetch(
    `${GITHUB_API}/repos/${config.repo}/releases/assets/${assetId}`,
    { method: "DELETE", headers: githubHeaders(config) },
  );
  if (response.ok || response.status === 404) return;
  throw new HttpError(502, `delete_${response.status}`);
}

function githubHeaders(config) {
  return {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${config.token}`,
    "User-Agent": "HyperGlow-Preset-Hub-EdgeOne",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}
