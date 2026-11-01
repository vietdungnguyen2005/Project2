import { createHash, createPrivateKey, randomUUID, sign } from "node:crypto";

export type TransportContext = { actor_ref: string; request_id: string; run_id: string; step: number;
  collection_ids: string[]; deadline_at: string };

export function signedRequest(context: TransportContext, path: "/ai/agent/step" | "/ai/knowledge/search" | "/ai/mapping/suggest" | "/ai/runs/control", payload: unknown): { body: string; headers: Headers } {
  const body = JSON.stringify(payload);
  if (Buffer.byteLength(body, "utf8") > (path==="/ai/mapping/suggest"?6_700_000:65_536)) throw new Error("INPUT_INVALID");
  const issued = Math.floor(Date.now()/1000);
  const expires = Math.min(issued+30, Math.floor(Date.parse(context.deadline_at)/1000));
  if (expires <= issued) throw new Error("DEADLINE_EXCEEDED");
  const requestDigest = createHash("sha256").update(["vmarket-ai-request:v1", "POST", path, "application/json",
    ...Array(7).fill(""), createHash("sha256").update(body).digest("hex"), ""].join("\n")).digest("hex");
  const kid = process.env.AI_SIGNING_KID ?? "";
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(kid)) throw new Error("CONFIGURATION_UNAVAILABLE");
  const encoded = Buffer.from(JSON.stringify({ version: 1, issuer: "vmarket-bff", audience: "vmarket-ai",
    mode: "integrated", kid, ...context, nonce: randomUUID(), issued_at: issued, expires_at: expires,
    operation: path === "/ai/agent/step" ? "agent_step" : path === "/ai/mapping/suggest" ? "mapping_suggest" : path === "/ai/runs/control" ? "run_control" : "knowledge_search", request_digest: requestDigest,
  })).toString("base64url");
  const key = createPrivateKey({ key: Buffer.from(process.env.AI_SIGNING_KEY_PKCS8_BASE64 ?? "", "base64"),
    type: "pkcs8", format: "der" });
  if (key.asymmetricKeyType !== "ed25519" || encoded.length > 4096) throw new Error("CONFIGURATION_UNAVAILABLE");
  return { body, headers: new Headers({ "Content-Type": "application/json", "X-Request-ID": context.request_id,
    "X-AI-Context": encoded, "X-AI-Signature": sign(null, Buffer.from("vmarket-ai-context:v1."+encoded), key).toString("base64url"),
  }) };
}
