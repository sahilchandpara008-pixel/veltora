// Creates a 'pending' order for a plan. The price always comes from the database, never the client.
// POST { plan_id }  (signed in)  ->  { order, checkout: { upi_vpa, payee_name }, upi_link }
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth) return json({ error: "Please sign in first" }, 401);
  const body = await req.json().catch(() => ({}));
  const planId = typeof body.plan_id === "string" ? body.plan_id : "";
  if (!/^[0-9a-f-]{36}$/i.test(planId)) return json({ error: "Choose a plan" }, 400);

  const asUser = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
  });
  const { data: order, error } = await asUser.rpc("create_order", { p_plan_id: planId });
  if (error) return json({ error: error.message }, 400);
  const { data: checkout } = await asUser.rpc("get_checkout_settings");

  const amount = (order.amount_paise / 100).toFixed(2);
  const upi = checkout?.upi_vpa
    ? `upi://pay?pa=${encodeURIComponent(checkout.upi_vpa)}&pn=${encodeURIComponent(checkout.payee_name ?? "Veltora")}` +
      `&tr=${encodeURIComponent(order.order_id)}&am=${amount}&cu=INR&tn=${encodeURIComponent(`Veltora ${order.order_id}`)}`
    : null;
  return json({
    order: {
      id: order.id, order_id: order.order_id, plan_id: order.plan_id, plan_name: order.plan_name,
      amount_paise: order.amount_paise, amount, status: order.status, method: order.method, created_at: order.created_at,
    },
    checkout,
    upi_link: upi,
  });
});
