// POST /api/presets/delete
//
// 匿名删除自己上传的云端预设。设备归属与上传一致：请求头 X-HyperGlow-Device 的
// SHA-256(device + OWNER_SALT) 必须等于条目的 owner。Cloudflare Worker 版同协议。
import { fail, json, preflight } from "../../_lib/respond.js";
import { HttpError, deletePreset } from "../../_lib/hub.js";

export function onRequestOptions() {
  return preflight();
}

export async function onRequestPost(context) {
  try {
    return json(await deletePreset(context.env, context.request));
  } catch (error) {
    if (error instanceof HttpError) return fail(error.status, error.code);
    console.error(`preset hub delete failure: ${error?.message || error}`);
    return fail(502, "github_failed");
  }
}
