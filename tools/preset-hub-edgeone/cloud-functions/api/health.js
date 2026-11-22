// /api/health
//
// 部署后先打这个接口，确认函数生效、GITHUB_TOKEN 读到了。只返回布尔值，
// 不泄露任何令牌内容或派生信息。
import { json } from "../_lib/respond.js";
import { configFromEnv } from "../_lib/hub.js";

export function onRequestGet(context) {
  const config = configFromEnv(context.env);
  return json({
    ok: true,
    service: "hyperglow-preset-hub-edgeone",
    repo: config.repo,
    tag: config.tag,
    upload: "POST /api/presets",
    list: "GET /api/presets",
    tokenConfigured: Boolean(config.token),
  });
}
