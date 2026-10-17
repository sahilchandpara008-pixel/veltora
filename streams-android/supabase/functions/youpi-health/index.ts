// Owner-only "Test connection" for Auto mode. Checks, each pass/fail:
//   1. YouPI URL and public key are filled in
//   2. {youpi_base_url}/youpi.js loads
//   3. YOUPI_WEBHOOK_SECRET exists in Edge Function secrets
// When every check passes, the result is saved; Auto mode can then be switched on (for 30 minutes).
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
  const auth = req.headers.get("Authorization") ?? "";
  const asUser = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });
  const { data: role } = await asUser.rpc("my_admin_role");
  if (role !== "owner") return json({ error: "Only the owner can do this" }, 403);

  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: s } = await admin.from("payment_settings")
    .select("youpi_base_url, youpi_public_key").eq("id", 1).maybeSingle();
  const base = s?.youpi_base_url ?? null;
  const checks: { id: string; label: string; pass: boolean; detail: string }[] = [];

  checks.push({
    id: "settings", label: "YouPI URL and public key are filled in",
    pass: !!base && !!s?.youpi_public_key,
    detail: !base ? "Add the YouPI base URL" : !s?.youpi_public_key ? "Add the YouPI public key" : "OK",
  });

  let jsPass = false, jsDetail = "Add the YouPI base URL first";
  if (base) {
    try {
      const res = await fetch(`${base}/youpi.js`, { signal: AbortSignal.timeout(8000), redirect: "follow" });
      const text = res.ok ? await res.text() : "";
      jsPass = res.ok && text.length > 0;
      jsDetail = res.ok ? (jsPass ? `Loaded (${text.length} bytes)` : "Empty file") : `HTTP ${res.status}`;
    } catch (e) {
      jsDetail = `Could not reach it: ${(e as Error).message}`.slice(0, 200);
    }
  }
  checks.push({ id: "youpi_js", label: "youpi.js loads from your YouPI server", pass: jsPass, detail: jsDetail });

  const hasSecret = !!Deno.env.get("YOUPI_WEBHOOK_SECRET");
  checks.push({
    id: "webhook_secret", label: "YOUPI_WEBHOOK_SECRET is saved in Supabase", pass: hasSecret,
    detail: hasSecret ? "OK" : "Add it in Supabase → Edge Functions → Secrets",
  });

  const allPass = checks.every((c) => c.pass);
  const report = { base_url: base, checked_at: new Date().toISOString(), checks };
  await admin.from("payment_settings")
    .update({ health_report: report, health_ok_at: allPass ? new Date().toISOString() : null }).eq("id", 1);
  return json({ ok: allPass, checks, webhook_url: `${url}/functions/v1/youpi-webhook` });
});
