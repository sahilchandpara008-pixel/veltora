// Manual mode: the customer reports the 12-digit UTR of their UPI payment.
// POST { order_id, utr }  (signed in)  ->  { order }   status becomes 'submitted' (or 'paid'/'review' when
// the owner's bank credit SMS with the same UTR already arrived).
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

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

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth) return json({ error: "Please sign in first" }, 401);
  const body = await req.json().catch(() => ({}));
  const orderId = typeof body.order_id === "string" ? body.order_id.trim() : "";
  const utr = typeof body.utr === "string" ? body.utr.replace(/\s/g, "") : "";
  if (!orderId) return json({ error: "Order not found" }, 400);
  if (!/^\d{12}$/.test(utr)) return json({ error: "The UTR / transaction ID has 12 digits" }, 400);

  const url = Deno.env.get("SUPABASE_URL")!;
  const asUser = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });
  const { data: order, error } = await asUser.rpc("submit_utr", { p_order_id: orderId, p_utr: utr });
  if (error) return json({ error: error.message }, 400);
  if (order.status === "paid") {
    await sendMetaPurchase(createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!), order.id);
  }
  return json({
    order: { id: order.id, order_id: order.order_id, status: order.status, utr: order.utr, amount_paise: order.amount_paise, plan_name: order.plan_name },
  });
});
