import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.117.3";

const cors = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store"
};

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: cors });
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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (req.method !== "POST") return json(405, { error: "Method not allowed" });

  let createdUserId: string | null = null;
  try {
    const url = Deno.env.get("SUPABASE_URL");
    if (!url) throw new Error("Supabase URL unavailable");
    const admin = createClient(url, secretKey(), {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false }
    });

    const authHeader = req.headers.get("authorization") || "";
    const token = authHeader.toLowerCase().startsWith("bearer ") ? authHeader.slice(7).trim() : "";
    if (!token) return json(401, { error: "Missing user token" });

    const { data: authData, error: authError } = await admin.auth.getUser(token);
    if (authError || !authData.user) return json(401, { error: "Invalid session" });

    const { data: adminMembership, error: membershipError } = await admin
      .from("memberships")
      .select("tenant_id,role")
      .eq("user_id", authData.user.id)
      .eq("role", "admin")
      .limit(1)
      .maybeSingle();
    if (membershipError) throw membershipError;
    if (!adminMembership) return json(403, { error: "Admin role required" });

    const body = await req.json();
    const email = String(body.email || "").trim().toLowerCase();
    const password = String(body.password || "");
    const role = String(body.role || "").trim().toLowerCase();
    const displayName = String(body.displayName || "").trim();
    const phone = String(body.phone || "").trim();
    const vehiclePlate = String(body.vehiclePlate || "").trim().toUpperCase();

    if (!/^\S+@\S+\.\S+$/.test(email)) return json(400, { error: "Valid email required" });
    if (password.length < 12) return json(400, { error: "Password must be at least 12 characters" });
    if (!new Set(["admin", "dispatcher", "driver"]).has(role)) return json(400, { error: "Role must be admin, dispatcher or driver" });
    if (role === "driver" && !displayName) return json(400, { error: "Driver display name required" });

    const { data: created, error: createError } = await admin.auth.admin.createUser({
      email,
      password,
      email_confirm: true
    });
    if (createError || !created.user) return json(400, { error: createError?.message || "Could not create user" });
    createdUserId = created.user.id;

    const { data: onboarded, error: onboardError } = await admin.rpc("onboard_tenant_user", {
      p_tenant_id: adminMembership.tenant_id,
      p_user_id: created.user.id,
      p_role: role,
      p_display_name: displayName || null,
      p_phone_e164: phone || null,
      p_vehicle_plate: vehiclePlate || null
    });

    if (onboardError) {
      await admin.auth.admin.deleteUser(created.user.id).catch(() => undefined);
      createdUserId = null;
      return json(400, { error: onboardError.message });
    }

    return json(201, {
      ok: true,
      user: { id: created.user.id, email, role },
      onboarding: onboarded
    });
  } catch (error) {
    if (createdUserId) {
      try {
        const url = Deno.env.get("SUPABASE_URL")!;
        const cleanup = createClient(url, secretKey(), { auth: { persistSession: false, autoRefreshToken: false } });
        await cleanup.auth.admin.deleteUser(createdUserId);
      } catch {}
    }
    console.error("admin-users failed", error instanceof Error ? error.message : "unknown error");
    return json(500, { error: "User onboarding failed" });
  }
});
