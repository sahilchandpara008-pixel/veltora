// Deletes the signed-in user's account and everything stored for it (Google Play requires
// in-app account deletion). POST with the user's session token -> { "deleted": true }
// Database rows go with the auth user (foreign keys cascade); files are removed here.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: { user } } = await admin.auth.getUser(token);
  if (!user) return json({ error: "Please sign in again." }, 401);

  // The owner account runs the app; it can't be removed from inside the app.
  const { data: role } = await admin.from("admin_emails")
    .select("role").eq("email", (user.email ?? "").toLowerCase()).maybeSingle();
  if (role?.role === "owner") return json({ error: "The owner account can't be deleted from the app." }, 400);

  // Files: Cloud Storage folder and creator uploads.
  const folders: [string, string][] = [
    ["user-files", user.id],
    ["images", `creator/${user.id}`],
    ["videos", `creator/${user.id}`],
  ];
  for (const [bucket, prefix] of folders) {
    for (;;) {
      const { data: files } = await admin.storage.from(bucket).list(prefix, { limit: 1000 });
      if (!files?.length) break;
      const { error } = await admin.storage.from(bucket).remove(files.map((f) => `${prefix}/${f.name}`));
      if (error) break;
    }
  }

  const { error } = await admin.auth.admin.deleteUser(user.id);
  if (error) return json({ error: "Couldn't delete the account. Please try again." }, 500);
  return json({ deleted: true });
});
