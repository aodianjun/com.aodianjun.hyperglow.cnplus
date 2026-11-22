// /api/presets
//
// GET  —— 公开读 GitHub Release 里的 presets.json（无需 Token）
// POST —— 匿名上传/更新。设备归属用 X-HyperGlow-Device 的 SHA-256 哈希，规则与
//         Cloudflare Worker 版完全一致；两个入口写同一份 Release，可互为主备。
import { fail, json, preflight } from "../../_lib/respond.js";
import { HttpError, listManifest, uploadPreset } from "../../_lib/hub.js";

export function onRequestOptions() {
  return preflight();
}

export async function onRequestGet(context) {
  try {
    return json(await listManifest(context.env));
  } catch (error) {
    return errorResponse(error);
  }
}

export async function onRequestPost(context) {
  try {
    return json(await uploadPreset(context.env, context.request));
  } catch (error) {
    return errorResponse(error);
  }
}

function errorResponse(error) {
  if (error instanceof HttpError) return fail(error.status, error.code);
  console.error(`preset hub failure: ${error?.message || error}`);
  return fail(502, "github_failed");
}
