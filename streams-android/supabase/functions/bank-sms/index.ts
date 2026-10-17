// Receives the bank's "credited" SMS from an SMS-forwarder app on the owner's phone and
// hands it to ingest_bank_sms(), which matches the amount to an open order.
// URL: .../functions/v1/bank-sms?key=<secret from Admin → Settings → Bank SMS auto-verify>
// Body: JSON ({"from": "...", "text": "..."} and similar key names), form data or plain text.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function sha256(s: string) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s.trim().toLowerCase()));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Server-side Meta Purchase, once per payment (event_id = order_id). */
async function sendMetaPurchase(admin: SupabaseClient, paymentId: string) {
  try {
    const { data: s } = await admin.from("app_settings").select("meta_enabled, meta_pixel_id").eq("id", 1).maybeSingle();
    const { data: secret } = await admin.from("meta_secrets").select("capi_token, test_event_code").eq("id", 1).maybeSingle();
    const pixel = s?.meta_pixel_id?.trim();
    if (!s?.meta_enabled || !pixel || !secret?.capi_token) return;
    const { data: p } = await admin.rpc("claim_meta_purchase_server", { p_payment_id: paymentId });
    if (!p) return;
    const userData: Record<string, unknown> = { external_id: [await sha256(p.user_id)] };
    if (p.email) userData.em = [await sha256(p.email)];
    const payload: Record<string, unknown> = {
      data: [{
        event_name: "Purchase", event_id: p.order_id, event_time: Math.floor(Date.now() / 1000), action_source: "website",
        user_data: userData,
        custom_data: { value: Number(p.value), currency: "INR", content_name: p.plan_name, content_type: "product", order_id: p.order_id },
      }],
    };
    if (secret.test_event_code) payload.test_event_code = secret.test_event_code;
    const res = await fetch(`https://graph.facebook.com/v23.0/${encodeURIComponent(pixel)}/events?access_token=${encodeURIComponent(secret.capi_token)}`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
    });
    if (!res.ok) console.error("Meta CAPI error", res.status, (await res.text()).slice(0, 300));
  } catch (e) {
    console.error("Meta purchase failed", e);
  }
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
  const r = data as { result?: string; payment_id?: string } | null;
  if (r?.result === "activated" && r.payment_id) await sendMetaPurchase(admin, r.payment_id);
  return json(data);
});
