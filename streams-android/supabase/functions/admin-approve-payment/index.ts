// Owner approves or rejects a 'submitted' or 'review' payment. Approve starts / extends the plan.
// POST { payment_id, action: "approve" | "reject", note? }  (owner only)  ->  { order }
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
  const paymentId = typeof body.payment_id === "string" ? body.payment_id : "";
  const action = body.action;
  if (!/^[0-9a-f-]{36}$/i.test(paymentId) || (action !== "approve" && action !== "reject")) {
    return json({ error: "Send payment_id and action approve/reject" }, 400);
  }
  const note = typeof body.note === "string" ? body.note.slice(0, 500) : null;

  const url = Deno.env.get("SUPABASE_URL")!;
  const asUser = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });
  // The database function checks that the caller is the owner.
  const { data: order, error } = await asUser.rpc("admin_review_payment", {
    p_payment_id: paymentId, p_approve: action === "approve", p_note: note,
  });
  if (error) return json({ error: error.message }, 403);
  if (order.status === "paid") {
    await sendMetaPurchase(createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!), order.id);
  }
  return json({ order: { id: order.id, order_id: order.order_id, status: order.status, paid_at: order.paid_at, admin_note: order.admin_note } });
});
