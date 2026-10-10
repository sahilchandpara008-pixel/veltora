// Website events for Meta (Facebook) Conversions API, sent server-side next to the browser Pixel.
// The Pixel ID comes from app_settings and the access token from meta_secrets (never sent to browsers).
// POST { event_name, event_id?, event_source_url?, fbp?, fbc?, custom_data? }
//   -> { sent: [{ event_name, event_id, value?, currency? }] }   (the browser fires the Pixel with the same event_id)
// CompleteRegistration and Purchase are claimed in the database, so each is reported once per account / payment,
// and Purchase values always come from the verified payment row, never from the browser.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const GRAPH = "https://graph.facebook.com/v23.0";
const ALLOWED = new Set(["PageView", "ViewContent", "Search", "CompleteRegistration", "Purchase", "VideoPlay", "TrailerComplete"]);
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
const str = (v: unknown, max = 200) => (typeof v === "string" && v.length > 0 ? v.slice(0, max) : undefined);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const body = await req.json().catch(() => ({}));
  const eventName = str(body.event_name, 40);
  if (!eventName || !ALLOWED.has(eventName)) return json({ error: "Unknown event" }, 400);

  const url = Deno.env.get("SUPABASE_URL")!;
  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: settings } = await admin.from("app_settings").select("meta_enabled, meta_pixel_id").eq("id", 1).maybeSingle();
  const { data: secret } = await admin.from("meta_secrets").select("capi_token, test_event_code").eq("id", 1).maybeSingle();
  const pixel = settings?.meta_pixel_id?.trim();
  if (!settings?.meta_enabled || !pixel || !secret?.capi_token) return json({ sent: [], skipped: "Meta tracking is off" });

  // The caller's account (if signed in); claims run as that user.
  const auth = req.headers.get("Authorization") ?? "";
  const { data: { user } } = await admin.auth.getUser(auth.replace(/^Bearer\s+/i, ""));
  const asUser = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });

  const userData: Record<string, unknown> = {
    client_ip_address: (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || undefined,
    client_user_agent: str(req.headers.get("user-agent"), 400),
    fbp: str(body.fbp), fbc: str(body.fbc),
  };
  if (user) {
    userData.external_id = [await sha256(user.id)];
    if (user.email) userData.em = [await sha256(user.email)];
  }
  const now = Math.floor(Date.now() / 1000);
  const base = { action_source: "website", event_time: now, event_source_url: str(body.event_source_url, 500), user_data: userData };
  const events: Record<string, unknown>[] = [];

  if (eventName === "CompleteRegistration") {
    if (!user) return json({ sent: [] });
    const { data: first } = await asUser.rpc("claim_meta_registration");
    if (first !== true) return json({ sent: [] });
    events.push({ ...base, event_name: "CompleteRegistration", event_id: `reg_${user.id}`, custom_data: { status: "registered" } });
  } else if (eventName === "Purchase") {
    if (!user) return json({ sent: [] });
    const { data: rows } = await asUser.rpc("claim_meta_purchases");
    for (const p of rows ?? []) {
      events.push({
        ...base, event_name: "Purchase", event_id: `purchase_${p.payment_id}`,
        custom_data: { value: Number(p.amount), currency: p.currency ?? "INR", content_name: p.plan_name ?? undefined, content_type: "product" },
      });
    }
  } else {
    const c = body.custom_data ?? {};
    events.push({
      ...base, event_name: eventName, event_id: str(body.event_id, 80) ?? crypto.randomUUID(),
      custom_data: {
        content_ids: Array.isArray(c.content_ids) ? c.content_ids.slice(0, 10).map((x: unknown) => String(x).slice(0, 64)) : undefined,
        content_name: str(c.content_name), content_type: str(c.content_type, 40), search_string: str(c.search_string),
      },
    });
  }
  if (!events.length) return json({ sent: [] });

  const payload: Record<string, unknown> = { data: events };
  if (secret.test_event_code) payload.test_event_code = secret.test_event_code;
  const res = await fetch(`${GRAPH}/${encodeURIComponent(pixel)}/events?access_token=${encodeURIComponent(secret.capi_token)}`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
  });
  if (!res.ok) {
    console.error("Meta CAPI error", res.status, (await res.text()).slice(0, 500));
    return json({ sent: [], error: "Meta rejected the event" }, 502);
  }
  return json({
    sent: events.map((e) => ({
      event_name: e.event_name, event_id: e.event_id,
      value: (e.custom_data as Record<string, unknown>)?.value, currency: (e.custom_data as Record<string, unknown>)?.currency,
    })),
  });
});
