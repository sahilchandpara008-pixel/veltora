// Marks 'pending' orders older than 30 minutes as 'expired' (and ended plans as expired).
// Runs every 15 minutes from the database scheduler (pg_cron job "veltora-expire-pending");
// this endpoint lets the owner (or a service-role caller) run it on demand.  POST -> { expired: n }
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
  const url = Deno.env.get("SUPABASE_URL")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const auth = req.headers.get("Authorization") ?? "";
  const token = auth.replace(/^Bearer\s+/i, "");
  if (token !== serviceKey) {
    const asUser = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });
    const { data: role } = await asUser.rpc("my_admin_role");
    if (role !== "owner") return json({ error: "Only the owner can do this" }, 403);
  }
  const admin = createClient(url, serviceKey);
  const { data, error } = await admin.rpc("expire_pending_payments");
  if (error) return json({ error: error.message }, 500);
  return json({ expired: data });
});
