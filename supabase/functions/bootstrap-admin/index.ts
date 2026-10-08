import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.117.3";

const headers = {
  "content-type": "text/html; charset=utf-8",
  "cache-control": "no-store"
};

function html(title: string, body: string, status = 200) {
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>body{font-family:Inter,system-ui,-apple-system,sans-serif;background:#f3f4f6;color:#111827;margin:0;padding:24px}.wrap{max-width:520px;margin:48px auto;background:#fff;border:1px solid #e5e7eb;border-radius:18px;padding:28px;box-shadow:0 16px 40px rgba(0,0,0,.08)}h1{margin:0 0 8px;font-size:26px}p{line-height:1.55;color:#4b5563}label{display:block;font-weight:700;margin-top:16px}input{width:100%;box-sizing:border-box;margin-top:7px;padding:13px 14px;border:1px solid #d1d5db;border-radius:10px;font-size:16px}button{width:100%;margin-top:22px;padding:14px;border:0;border-radius:10px;background:#111827;color:white;font-weight:800;font-size:16px;cursor:pointer}.ok{padding:12px 14px;border-radius:10px;background:#ecfdf5;color:#065f46}.err{padding:12px 14px;border-radius:10px;background:#fef2f2;color:#991b1b}.small{font-size:13px;color:#6b7280}</style></head><body><div class="wrap">${body}</div></body></html>`, { status, headers });
}

function secretKey() {
  const modern = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (modern) {
    const parsed = JSON.parse(modern);
    if (parsed.default) return parsed.default;
  }
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!legacy) throw new Error("Supabase secret key unavailable");
  return legacy;
}

async function sha256(value: string) {
  const data = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function form(message = "") {
  return html("SnapNest Dispatch Admin Setup", `<h1>SnapNest Dispatch</h1><p>Create the first administrator account. This page permanently locks after the first admin is created.</p>${message}<form method="post"><label>Email<input name="email" type="email" autocomplete="email" required></label><label>Password<input name="password" type="password" minlength="12" autocomplete="new-password" required></label><label>One-time bootstrap code<input name="code" type="password" autocomplete="one-time-code" required></label><button type="submit">Create first admin</button></form><p class="small">Use a password of at least 12 characters. The bootstrap code can only be used once.</p>`);
}

Deno.serve(async (req: Request) => {
  try {
    if (req.method !== "GET" && req.method !== "POST") return new Response("Method not allowed", { status: 405 });

    const url = Deno.env.get("SUPABASE_URL");
    if (!url) throw new Error("Supabase URL unavailable");
    const admin = createClient(url, secretKey(), {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false }
    });

    const { data: bootstrap, error: bootstrapError } = await admin
      .from("system_bootstrap")
      .select("code_hash,claimed_at")
      .eq("key", "initial_admin")
      .maybeSingle();
    if (bootstrapError) throw bootstrapError;
    if (!bootstrap) return html("Setup unavailable", `<h1>Setup unavailable</h1><div class="err">Bootstrap has not been configured.</div>`, 503);
    if (bootstrap.claimed_at) return html("Setup complete", `<h1>Setup complete</h1><div class="ok">The first SnapNest Dispatch administrator already exists. This bootstrap page is permanently locked.</div>`);

    if (req.method === "GET") return form();

    const contentType = req.headers.get("content-type") || "";
    let email = "", password = "", code = "";
    if (contentType.includes("application/json")) {
      const body = await req.json();
      email = String(body.email || "").trim().toLowerCase();
      password = String(body.password || "");
      code = String(body.code || "");
    } else {
      const body = await req.formData();
      email = String(body.get("email") || "").trim().toLowerCase();
      password = String(body.get("password") || "");
      code = String(body.get("code") || "");
    }

    if (!/^\S+@\S+\.\S+$/.test(email)) return form(`<div class="err">Enter a valid email address.</div>`);
    if (password.length < 12) return form(`<div class="err">Password must be at least 12 characters.</div>`);
    if (code.length < 16) return form(`<div class="err">Bootstrap code is invalid.</div>`);

    const submittedHash = await sha256(code);
    if (submittedHash !== bootstrap.code_hash) return form(`<div class="err">Bootstrap code is invalid.</div>`);

    const { count: memberCount, error: memberError } = await admin
      .from("memberships")
      .select("user_id", { count: "exact", head: true });
    if (memberError) throw memberError;
    if ((memberCount || 0) > 0) return html("Setup locked", `<h1>Setup locked</h1><div class="err">A tenant member already exists, so bootstrap has been disabled.</div>`, 409);

    const { data: created, error: createError } = await admin.auth.admin.createUser({
      email,
      password,
      email_confirm: true
    });
    if (createError || !created.user) return form(`<div class="err">${createError?.message || "Could not create admin user."}</div>`);

    const { error: claimError } = await admin.rpc("claim_initial_admin", {
      p_user_id: created.user.id,
      p_code_hash: submittedHash
    });

    if (claimError) {
      await admin.auth.admin.deleteUser(created.user.id).catch(() => undefined);
      return form(`<div class="err">${claimError.message}</div>`);
    }

    return html("Admin created", `<h1>Admin created</h1><div class="ok">Your SnapNest Dispatch administrator account is ready.</div><p><strong>${email.replace(/[&<>]/g, "")}</strong> now has the admin role.</p><p class="small">This setup page is now locked. Use the normal Dispatch login from here onward.</p>`);
  } catch (error) {
    console.error("bootstrap-admin failed", error instanceof Error ? error.message : "unknown error");
    return html("Setup error", `<h1>Setup error</h1><div class="err">The setup could not be completed. Try again or check the function logs.</div>`, 500);
  }
});
