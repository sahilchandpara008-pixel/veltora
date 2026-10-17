// Server hook for a trusted payment status from your bank's UPI transaction-status API or payment
// callback (verification_source = 'provider_api'). Not used until PROVIDER_WEBHOOK_SECRET is set.
//   X-Provider-Signature: "sha256=" + hex(HMAC-SHA256(raw body, PROVIDER_WEBHOOK_SECRET))
//   X-Provider-Timestamp: unix seconds (or ms / ISO date); older than 5 minutes is rejected
// Body: { orderId, status: "SUCCESS" | "FAILURE", amountPaise (or amount in rupees), utr, providerRef }
// The database decides (provider_payment_update): amount must match, a UTR works once, repeats change
// nothing. Ads accounts are activated; organic accounts go to 'review' for the owner.
// When your bank gives a different format, adapt only the parsing below.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const enc = new TextEncoder();

async function hmacHex(secret: string, body: Uint8Array) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, body));
  return [...sig].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time string comparison. */
function safeEqual(a: string, b: string) {
  const x = enc.encode(a), y = enc.encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

function timestampMs(h: string | null): number | null {
  if (!h) return null;
  const t = h.trim();
  if (/^\d+$/.test(t)) {
    const n = Number(t);
    return n > 1e12 ? n : n * 1000;
  }
  const d = Date.parse(t);
  return Number.isNaN(d) ? null : d;
}

async function sha256(s: string) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s.trim().toLowerCase()));
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

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ ok: true, info: "Veltora provider payment hook. POST only." });
  const secret = Deno.env.get("PROVIDER_WEBHOOK_SECRET");
  if (!secret) return json({ error: "Provider hook is not configured" }, 503);

  const raw = new Uint8Array(await req.arrayBuffer());
  const given = (req.headers.get("X-Provider-Signature") ?? "").trim();
  const expected = "sha256=" + (await hmacHex(secret, raw));
  if (!safeEqual(given.toLowerCase(), expected)) return json({ error: "bad signature" }, 401);

  const ts = timestampMs(req.headers.get("X-Provider-Timestamp"));
  if (ts === null || Math.abs(Date.now() - ts) > 5 * 60 * 1000) return json({ error: "stale or missing timestamp" }, 401);

  let body: Record<string, unknown>;
  try {
    body = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    return json({ error: "bad json" }, 400);
  }
  const orderId = typeof body.orderId === "string" ? body.orderId : "";
  if (!orderId) return json({ error: "missing orderId" }, 400);
  const status = String(body.status ?? "").toUpperCase();
  let paise: number | null = null;
  if (body.amountPaise !== undefined && body.amountPaise !== null && body.amountPaise !== "") paise = Number(body.amountPaise);
  else if (body.amount !== undefined && body.amount !== null && body.amount !== "") paise = Math.round(Number(body.amount) * 100);
  if (paise !== null && !Number.isFinite(paise)) paise = null;
  const utr = body.utr == null ? null : String(body.utr);
  const providerRef = body.providerRef == null ? null : String(body.providerRef).slice(0, 120);

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data, error } = await admin.rpc("provider_payment_update", {
    p_order_id: orderId, p_status: status, p_amount_paise: paise === null ? null : Math.trunc(paise),
    p_utr: utr, p_provider_ref: providerRef,
  });
  if (error) {
    console.error("provider_payment_update failed", error.message);
    return json({ error: "could not record payment" }, 500);
  }
  if (data?.result === "paid" && data.payment_id) await sendMetaPurchase(admin, data.payment_id);
  return json({ ok: true, ...data });
});
