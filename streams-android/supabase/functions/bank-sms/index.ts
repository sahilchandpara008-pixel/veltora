// Receives the bank's "credited" SMS from an SMS-forwarder app on the owner's phone and
// hands it to ingest_bank_sms(), which matches the amount to an open order.
// URL: .../functions/v1/bank-sms?key=<secret from Admin → Settings → Bank SMS auto-verify>
// Body: JSON ({"from": "...", "text": "..."} and similar key names), form data or plain text.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const TEXT_KEYS = ["text", "message", "msg", "body", "content", "sms", "smstext", "sms_text", "messagebody"];
const FROM_KEYS = ["from", "sender", "address", "phone", "number", "source", "originatingaddress", "title"];

function pick(obj: Record<string, unknown>, keys: string[]): string {
  const lower: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) lower[k.toLowerCase()] = v;
  for (const k of keys) {
    const v = lower[k];
    if (typeof v === "string" && v.trim()) return v.trim();
    if (typeof v === "number") return String(v);
  }
  return "";
}

async function readBody(req: Request): Promise<Record<string, unknown>> {
  const type = req.headers.get("content-type") ?? "";
  const raw = await req.text();
  if (type.includes("application/x-www-form-urlencoded")) return Object.fromEntries(new URLSearchParams(raw));
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object") {
      // Some forwarders wrap the message: {"data": {...}} or {"sms": {...}}
      for (const k of ["data", "sms", "message", "payload"]) {
        const inner = (parsed as Record<string, unknown>)[k];
        if (inner && typeof inner === "object") return { ...(parsed as object), ...(inner as object) };
      }
      return parsed as Record<string, unknown>;
    }
  } catch { /* not JSON */ }
  return { text: raw };
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: true, info: "Veltora bank SMS endpoint. Send the SMS with POST." });
  const url = new URL(req.url);
  const key = url.searchParams.get("key") ?? req.headers.get("x-veltora-key") ?? "";
  if (!key) return json({ ok: false, error: "missing key" }, 401);

  const body = await readBody(req);
  const text = pick(body, TEXT_KEYS);
  const from = url.searchParams.get("from") ?? pick(body, FROM_KEYS);
  if (!text) return json({ ok: false, error: "no SMS text in the request" }, 400);

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data, error } = await admin.rpc("ingest_bank_sms", { p_key: key, p_sender: from, p_text: text });
  if (error) return json({ ok: false, error: error.message }, 500);
  if (data && (data as { ok?: boolean }).ok === false) return json(data, 401);
  return json(data);
});
