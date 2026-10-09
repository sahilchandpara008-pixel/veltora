// Signs a trailer link for an app install that came from Meta ads, before the person signs in.
// POST { "title_id": "<uuid>", "install_id": "<uuid>" } -> { "url": "<signed url, 6 h>" }
// Only trailers; full videos still need sign-in + plan (storage RLS / can_stream).
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const { title_id, install_id } = await req.json().catch(() => ({}));
  if (typeof title_id !== "string" || !UUID.test(title_id) || typeof install_id !== "string" || !UUID.test(install_id)) {
    return json({ error: "Bad request" }, 400);
  }

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  const { data: install } = await admin.from("attribution_installs")
    .select("source").eq("install_id", install_id).maybeSingle();
  if (install?.source !== "meta") return json({ error: "Sign in to watch this trailer." }, 403);

  const { data: title } = await admin.from("titles")
    .select("published, tier, trailer_path").eq("id", title_id).maybeSingle();
  if (!title || !title.published || title.tier === "hidden_premium" || !title.trailer_path) {
    return json({ error: "This trailer isn't available." }, 404);
  }

  const { data: signed, error } = await admin.storage.from("videos").createSignedUrl(title.trailer_path, 6 * 3600);
  if (error || !signed) return json({ error: "This trailer isn't available." }, 500);
  return json({ url: signed.signedUrl });
});
