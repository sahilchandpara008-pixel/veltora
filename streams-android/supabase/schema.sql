-- =====================================================================
-- VELTORA — complete database setup
-- Paste this whole file into Supabase → SQL Editor → New query → Run.
-- Safe to run once on a fresh project.
--
-- BEFORE RUNNING: replace YOUR_OWNER_EMAIL@example.com (near the bottom)
-- with the email address you will use to log in to the admin area.
-- =====================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------
-- 1. ADMIN ALLOW-LIST
-- ---------------------------------------------------------------------
create table public.admin_emails (
  email      text primary key check (email = lower(email)),
  role       text not null check (role in ('owner', 'manager')),
  created_at timestamptz not null default now()
);

-- Role of the signed-in user ('owner' / 'manager' / null). Used everywhere.
create or replace function public.my_admin_role()
returns text language sql stable security definer set search_path = public as $$
  select role from public.admin_emails
  where email = lower(coalesce(auth.jwt() ->> 'email', ''))
$$;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select public.my_admin_role() is not null
$$;

create or replace function public.is_owner()
returns boolean language sql stable security definer set search_path = public as $$
  select public.my_admin_role() = 'owner'
$$;

alter table public.admin_emails enable row level security;
create policy "admins read team"   on public.admin_emails for select using (public.is_admin());
create policy "owner adds manager" on public.admin_emails for insert with check (public.is_owner() and role = 'manager');
create policy "owner removes manager" on public.admin_emails for delete using (public.is_owner() and role = 'manager');

-- ---------------------------------------------------------------------
-- 2. SETTINGS (UPI ID etc. — never hardcoded in the app)
-- ---------------------------------------------------------------------
create table public.app_settings (
  id         int primary key default 1 check (id = 1),
  upi_id     text not null,
  payee_name text not null,
  updated_at timestamptz not null default now()
);
insert into public.app_settings (upi_id, payee_name) values ('', 'Veltora');

alter table public.app_settings enable row level security;
create policy "anyone reads settings" on public.app_settings for select using (true);
create policy "owner edits settings"  on public.app_settings for update using (public.is_owner()) with check (public.is_owner());

-- ---------------------------------------------------------------------
-- 3. PLANS
-- ---------------------------------------------------------------------
create table public.subscription_plans (
  id            uuid primary key default gen_random_uuid(),
  name          text not null,
  duration_label text not null,
  duration_days int  not null check (duration_days > 0),
  price         numeric(10,2) not null check (price > 0),
  currency      text not null default 'INR',
  active        boolean not null default true,
  sort_order    int not null default 0
);
insert into public.subscription_plans (name, duration_label, duration_days, price, sort_order) values
  ('Trial',         '3 Days',   3,   99, 1),
  ('Silver Plan',   '7 Days',   7,  149, 2),
  ('Gold Plan',     '1 Month', 30,  259, 3),
  ('Platinum Plan', '6 Months',180, 599, 4),
  ('Diamond Plan',  '1 Year', 365,  999, 5);

alter table public.subscription_plans enable row level security;
create policy "anyone reads active plans" on public.subscription_plans for select using (active or public.is_admin());
create policy "owner manages plans"       on public.subscription_plans for all using (public.is_owner()) with check (public.is_owner());

-- ---------------------------------------------------------------------
-- 4. SUBSCRIPTIONS (one row per user; only server functions write it)
-- ---------------------------------------------------------------------
create table public.subscriptions (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  user_email text,
  plan_id    uuid references public.subscription_plans(id),
  status     text not null check (status in ('active', 'expired')),
  expires_at timestamptz not null,
  updated_at timestamptz not null default now()
);

alter table public.subscriptions enable row level security;
create policy "user reads own subscription" on public.subscriptions for select using (user_id = auth.uid() or public.is_admin());
-- No insert/update/delete policies: customers can NEVER write here.

create or replace function public.has_active_subscription()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.subscriptions
    where user_id = auth.uid() and status = 'active' and expires_at > now()
  )
$$;

-- Flips passed subscriptions to 'expired'. Scheduled below (pg_cron) and also
-- run whenever an admin opens the dashboard.
create or replace function public.expire_subscriptions()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.subscriptions set status = 'expired', updated_at = now()
  where status = 'active' and expires_at <= now();
  get diagnostics n = row_count;
  return n;
end $$;

-- ---------------------------------------------------------------------
-- 5. PAYMENTS (manual UPI + UTR verification)
-- ---------------------------------------------------------------------
create table public.payments (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  user_email  text,
  plan_id     uuid not null references public.subscription_plans(id),
  plan_name   text,
  amount      numeric(10,2),
  currency    text,
  reference   text not null unique,
  status      text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  admin_note  text,
  created_at  timestamptz not null default now(),
  reviewed_at timestamptz,
  reviewed_by text
);
-- Only one pending payment per user at a time.
create unique index payments_one_pending_per_user on public.payments (user_id) where status = 'pending';

-- Whatever the client sends, price/email/status come from the server.
create or replace function public.payments_before_insert()
returns trigger language plpgsql security definer set search_path = public as $$
declare p public.subscription_plans;
begin
  select * into p from public.subscription_plans where id = new.plan_id and active;
  if not found then raise exception 'This plan is not available'; end if;
  new.reference   := upper(regexp_replace(new.reference, '\s', '', 'g'));
  if new.reference !~ '^[A-Z0-9]{6,35}$' then
    raise exception 'Reference number should be 6-35 letters/digits (check your UPI app)';
  end if;
  new.user_id     := auth.uid();
  new.user_email  := auth.jwt() ->> 'email';
  new.plan_name   := p.name;
  new.amount      := p.price;
  new.currency    := p.currency;
  new.status      := 'pending';
  new.admin_note  := null;
  new.reviewed_at := null;
  new.reviewed_by := null;
  new.created_at  := now();
  return new;
end $$;
create trigger payments_before_insert before insert on public.payments
  for each row execute function public.payments_before_insert();

alter table public.payments enable row level security;
create policy "user inserts own pending payment" on public.payments for insert
  with check (auth.uid() is not null and user_id = auth.uid() and status = 'pending');
create policy "user reads own payments" on public.payments for select
  using (user_id = auth.uid() or public.is_admin());
create policy "admins update payments" on public.payments for update
  using (public.is_admin()) with check (public.is_admin());

-- Customer submits a UTR. Friendly errors for duplicates.
create or replace function public.submit_payment(p_plan_id uuid, p_reference text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if exists (select 1 from public.payments where user_id = auth.uid() and status = 'pending') then
    raise exception 'You already have a payment waiting for verification';
  end if;
  if exists (select 1 from public.payments where reference = upper(regexp_replace(p_reference, '\s', '', 'g'))) then
    raise exception 'This reference number has already been submitted';
  end if;
  insert into public.payments (plan_id, reference) values (p_plan_id, p_reference) returning * into r;
  return r;
end $$;

-- Admin approves. Only place in the whole system that grants a subscription.
create or replace function public.approve_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay  public.payments;
  plan public.subscription_plans;
  cur  public.subscriptions;
  base timestamptz;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;

  -- Atomic "still pending?" check: a double tap cannot approve twice.
  update public.payments
     set status = 'approved', reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'pending'
  returning * into pay;
  if not found then raise exception 'This payment was already processed'; end if;

  select * into plan from public.subscription_plans where id = pay.plan_id;
  select * into cur  from public.subscriptions where user_id = pay.user_id for update;

  -- new_expiry = max(now, current active expiry) + plan days (keeps unused time)
  base := greatest(now(), coalesce(case when cur.status = 'active' then cur.expires_at end, now()));

  insert into public.subscriptions (user_id, user_email, plan_id, status, expires_at, updated_at)
  values (pay.user_id, pay.user_email, pay.plan_id, 'active', base + make_interval(days => plan.duration_days), now())
  on conflict (user_id) do update
     set plan_id = excluded.plan_id, user_email = excluded.user_email, status = 'active',
         expires_at = excluded.expires_at, updated_at = now();
  return pay;
end $$;

create or replace function public.reject_payment(p_payment_id uuid, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if coalesce(length(trim(p_note)), 0) < 3 then raise exception 'Please type a short reason'; end if;
  update public.payments
     set status = 'rejected', admin_note = trim(p_note), reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'pending'
  returning * into pay;
  if not found then raise exception 'This payment was already processed'; end if;
  return pay;
end $$;

-- ---------------------------------------------------------------------
-- 6. CONTENT: channels, titles, episodes
-- ---------------------------------------------------------------------
create table public.channels (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  description text not null default '',
  is_premium  boolean not null default false,
  sort_order  int not null default 0,
  created_at  timestamptz not null default now()
);
alter table public.channels enable row level security;
create policy "anyone reads channels" on public.channels for select using (true);
create policy "admins manage channels" on public.channels for all using (public.is_admin()) with check (public.is_admin());

create table public.titles (
  id               uuid primary key default gen_random_uuid(),
  kind             text not null check (kind in ('movie', 'series')),
  name             text not null,
  description      text not null default '',
  channel_id       uuid references public.channels(id) on delete set null,
  cover_path       text,              -- in public bucket "images"
  trailer_path     text,              -- in private bucket "videos"
  video_path       text,              -- in private bucket "videos" (movies)
  duration_seconds int,
  tier             text not null default 'free' check (tier in ('free', 'premium', 'hidden_premium')),
  published        boolean not null default false,   -- new uploads are drafts
  featured         boolean not null default false,
  view_count       bigint not null default 0,
  preview_count    bigint not null default 0,
  created_at       timestamptz not null default now()
);
alter table public.titles enable row level security;
-- Drafts: admins only. Hidden premium: subscribers (and admins) only.
create policy "visible titles" on public.titles for select using (
  public.is_admin()
  or (published and (tier <> 'hidden_premium' or public.has_active_subscription()))
);
create policy "admins manage titles" on public.titles for all using (public.is_admin()) with check (public.is_admin());

create table public.episodes (
  id               uuid primary key default gen_random_uuid(),
  title_id         uuid not null references public.titles(id) on delete cascade,
  episode_number   int not null,
  name             text not null,
  description      text not null default '',
  video_path       text not null,
  duration_seconds int,
  created_at       timestamptz not null default now(),
  unique (title_id, episode_number)
);
alter table public.episodes enable row level security;
create policy "episodes of visible titles" on public.episodes for select using (
  exists (select 1 from public.titles t where t.id = title_id)   -- titles RLS applies here
);
create policy "admins manage episodes" on public.episodes for all using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------------
-- 7. MARKETING CAMPAIGNS
-- ---------------------------------------------------------------------
create table public.campaigns (
  id         uuid primary key default gen_random_uuid(),
  name       text not null,
  message    text not null default '',
  active     boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.campaigns enable row level security;
create policy "anyone reads active campaigns" on public.campaigns for select using (active or public.is_admin());
create policy "admins manage campaigns" on public.campaigns for all using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------------
-- 8. VIEW COUNTERS + ADMIN STATS
-- ---------------------------------------------------------------------
create or replace function public.record_view(p_title_id uuid, p_preview boolean)
returns void language sql security definer set search_path = public as $$
  update public.titles
     set view_count    = view_count    + case when p_preview then 0 else 1 end,
         preview_count = preview_count + case when p_preview then 1 else 0 end
   where id = p_title_id and published;
$$;

create or replace function public.admin_stats()
returns json language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  perform public.expire_subscriptions();
  return json_build_object(
    'total_videos',        (select count(*) from public.titles),
    'exclusive_videos',    (select count(*) from public.titles where tier = 'hidden_premium'),
    'total_views',         (select coalesce(sum(view_count), 0) from public.titles),
    'preview_views',       (select coalesce(sum(preview_count), 0) from public.titles),
    'paying_subscribers',  (select count(*) from public.subscriptions where status = 'active' and expires_at > now()),
    'expired_subscribers', (select count(*) from public.subscriptions where status = 'expired' or expires_at <= now()),
    'pending_payments',    (select count(*) from public.payments where status = 'pending')
  );
end $$;

-- ---------------------------------------------------------------------
-- 9. STORAGE: "images" is public, "videos" is private
-- ---------------------------------------------------------------------
insert into storage.buckets (id, name, public) values ('images', 'images', true)  on conflict do nothing;
insert into storage.buckets (id, name, public) values ('videos', 'videos', false) on conflict do nothing;

-- Who may stream a given file in the "videos" bucket? (never guests)
--   trailers of visible titles ............ any signed-in user
--   free title videos ..................... any signed-in user
--   premium videos ........................ subscribers; others only if the title has
--                                           no trailer (the app stops at 30 seconds)
--   hidden premium ........................ subscribers only (titles RLS hides it)
create or replace function public.can_stream(object_name text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare t public.titles;
begin
  if public.is_admin() then return true; end if;
  -- Browsing is open to everyone, but watching anything needs an account.
  if auth.uid() is null then return false; end if;

  select * into t from public.titles
   where published and (trailer_path = object_name or video_path = object_name)
   limit 1;
  if not found then
    select tt.* into t from public.episodes e join public.titles tt on tt.id = e.title_id
     where tt.published and e.video_path = object_name limit 1;
    if not found then return false; end if;
  end if;

  if t.tier = 'hidden_premium' then return public.has_active_subscription(); end if;
  if t.trailer_path = object_name then return true; end if;
  if t.tier = 'free' then return true; end if;
  return public.has_active_subscription() or t.trailer_path is null;
end $$;

create policy "images public read" on storage.objects for select using (bucket_id = 'images');
create policy "admins write images" on storage.objects for insert with check (bucket_id = 'images' and public.is_admin());
create policy "admins update images" on storage.objects for update using (bucket_id = 'images' and public.is_admin());
create policy "admins delete images" on storage.objects for delete using (bucket_id = 'images' and public.is_admin());

create policy "stream videos" on storage.objects for select using (bucket_id = 'videos' and public.can_stream(name));
create policy "admins write videos" on storage.objects for insert with check (bucket_id = 'videos' and public.is_admin());
create policy "admins update videos" on storage.objects for update using (bucket_id = 'videos' and public.is_admin());
create policy "admins delete videos" on storage.objects for delete using (bucket_id = 'videos' and public.is_admin());

-- ---------------------------------------------------------------------
-- 10. OWNER ACCOUNT  ← CHANGE THIS EMAIL
-- ---------------------------------------------------------------------
insert into public.admin_emails (email, role) values (lower('perfumwala@gmail.com'), 'owner');

-- ---------------------------------------------------------------------
-- 11. FUNCTION ACCESS — admin/payment functions are not callable by guests,
--     internal ones are not callable through the API at all.
-- ---------------------------------------------------------------------
revoke execute on function public.admin_stats() from public, anon;
revoke execute on function public.approve_payment(uuid) from public, anon;
revoke execute on function public.reject_payment(uuid, text) from public, anon;
revoke execute on function public.submit_payment(uuid, text) from public, anon;
grant execute on function public.admin_stats() to authenticated;
grant execute on function public.approve_payment(uuid) to authenticated;
grant execute on function public.reject_payment(uuid, text) to authenticated;
grant execute on function public.submit_payment(uuid, text) to authenticated;
revoke execute on function public.expire_subscriptions() from public, anon, authenticated;
revoke execute on function public.payments_before_insert() from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- 12. Expiry sweep every 30 minutes (pg_cron is available on all Supabase plans).
-- ---------------------------------------------------------------------
create extension if not exists pg_cron;
select cron.schedule('expire-subscriptions', '*/30 * * * *', 'select public.expire_subscriptions()');

-- ---------------------------------------------------------------------
-- 13. PREMIUM CLOUD STORAGE — each member gets a private folder
--     user-files/<user id>/... Uploads need an active plan and stay under
--     app_settings.cloud_quota_gb. Deleting always works (to free space).
-- ---------------------------------------------------------------------
alter table public.app_settings add column if not exists cloud_quota_gb int not null default 2048 check (cloud_quota_gb > 0);

insert into storage.buckets (id, name, public) values ('user-files', 'user-files', false) on conflict do nothing;

create or replace function public.cloud_quota_bytes()
returns bigint language sql stable security definer set search_path = public as $$
  select cloud_quota_gb::bigint * 1024 * 1024 * 1024 from public.app_settings where id = 1
$$;

create or replace function public.my_cloud_usage()
returns bigint language sql stable security definer set search_path = public as $$
  select coalesce(sum((o.metadata ->> 'size')::bigint), 0)::bigint
  from storage.objects o
  where o.bucket_id = 'user-files' and (storage.foldername(o.name))[1] = auth.uid()::text
$$;

create or replace function public.my_files()
returns table (name text, size bigint, mimetype text, created_at timestamptz)
language sql stable security definer set search_path = public as $$
  select o.name, coalesce((o.metadata ->> 'size')::bigint, 0), o.metadata ->> 'mimetype', o.created_at
  from storage.objects o
  where auth.uid() is not null
    and o.bucket_id = 'user-files' and (storage.foldername(o.name))[1] = auth.uid()::text
  order by o.created_at desc
$$;

revoke execute on function public.my_cloud_usage() from public, anon;
revoke execute on function public.my_files() from public, anon;
grant execute on function public.my_cloud_usage() to authenticated;
grant execute on function public.my_files() to authenticated;

create policy "cloud: read own files" on storage.objects for select
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "cloud: premium uploads within quota" on storage.objects for insert
  with check (
    bucket_id = 'user-files'
    and (storage.foldername(name))[1] = auth.uid()::text
    and public.has_active_subscription()
    and public.my_cloud_usage() < public.cloud_quota_bytes()
  );
create policy "cloud: delete own files" on storage.objects for delete
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);

-- ---------------------------------------------------------------------
-- 14. AUTOMATIC UPI CONFIRMATION (no payment gateway)
--     1. App calls start_upi_payment(plan) → an 'initiated' row with our ref.
--     2. App opens the UPI app; the UPI app hands back a response string
--        (txnId, ApprovalRefNo/UTR, Status).
--     3. App calls confirm_upi_payment(id, response) → if Status=SUCCESS the
--        plan is applied immediately and the row is marked approved, method 'upi_auto'.
--     NOTE: that response comes from the customer's phone, so it cannot be
--     proven genuine. Admins match auto payments against the bank statement
--     and can revoke_payment() one that never arrived (takes the days back).
-- ---------------------------------------------------------------------
alter table public.payments add column if not exists method       text not null default 'manual';
alter table public.payments add column if not exists txn_id       text;
alter table public.payments add column if not exists upi_response text;
create unique index if not exists payments_txn_id_unique on public.payments (upper(txn_id)) where txn_id is not null;
alter table public.payments drop constraint if exists payments_status_check;
alter table public.payments add constraint payments_status_check
  check (status in ('initiated', 'pending', 'approved', 'rejected', 'revoked'));
alter table public.payments drop constraint if exists payments_method_check;
alter table public.payments add constraint payments_method_check check (method in ('manual', 'upi_auto'));

-- Client inserts are forced to a harmless state: a manual 'pending' UTR claim,
-- or an 'initiated' auto payment (which only confirm_upi_payment can complete).
create or replace function public.payments_before_insert()
returns trigger language plpgsql security definer set search_path = public as $$
declare p public.subscription_plans;
begin
  select * into p from public.subscription_plans where id = new.plan_id and active;
  if not found then raise exception 'This plan is not available'; end if;
  new.reference   := upper(regexp_replace(new.reference, '\s', '', 'g'));
  if new.reference !~ '^[A-Z0-9]{6,35}$' then
    raise exception 'Reference number should be 6-35 letters/digits (check your UPI app)';
  end if;
  new.user_id     := auth.uid();
  new.user_email  := auth.jwt() ->> 'email';
  new.plan_name   := p.name;
  new.amount      := p.price;
  new.currency    := p.currency;
  new.status      := case when new.status = 'initiated' then 'initiated' else 'pending' end;
  new.method      := case when new.status = 'initiated' then 'upi_auto' else 'manual' end;
  new.txn_id      := null;
  new.upi_response := null;
  new.admin_note  := null;
  new.reviewed_at := null;
  new.reviewed_by := null;
  new.created_at  := now();
  return new;
end $$;

drop policy if exists "user inserts own pending payment" on public.payments;
create policy "user inserts own pending payment" on public.payments for insert
  with check (auth.uid() is not null and user_id = auth.uid() and status in ('pending', 'initiated'));

-- Shared by manual approval and automatic confirmation.
-- new_expiry = max(now, current active expiry) + plan days (keeps unused time)
create or replace function public.grant_plan_for_payment(pay public.payments)
returns void language plpgsql security definer set search_path = public as $$
declare
  plan public.subscription_plans;
  cur  public.subscriptions;
  base timestamptz;
begin
  select * into plan from public.subscription_plans where id = pay.plan_id;
  select * into cur  from public.subscriptions where user_id = pay.user_id for update;
  base := greatest(now(), coalesce(case when cur.status = 'active' then cur.expires_at end, now()));
  insert into public.subscriptions (user_id, user_email, plan_id, status, expires_at, updated_at)
  values (pay.user_id, pay.user_email, pay.plan_id, 'active', base + make_interval(days => plan.duration_days), now())
  on conflict (user_id) do update
     set plan_id = excluded.plan_id, user_email = excluded.user_email, status = 'active',
         expires_at = excluded.expires_at, updated_at = now();
end $$;

create or replace function public.approve_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  -- Atomic "still pending?" check: a double tap cannot approve twice.
  update public.payments
     set status = 'approved', reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'pending'
  returning * into pay;
  if not found then raise exception 'This payment was already processed'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- Step 1: the app asks for a fresh payment reference before opening the UPI app.
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  -- An unfinished earlier attempt never granted anything, so it can go.
  delete from public.payments where user_id = auth.uid() and status = 'initiated';
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id, 'STR' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14)), 'initiated')
  returning * into r;
  return r;
end $$;

-- Step 2: the app forwards the UPI app's response. SUCCESS → plan applied now.
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  kv     text;
  f      jsonb := '{}';
  st     text;
  txn    text;
  pay    public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;

  -- "txnId=..&responseCode=00&Status=SUCCESS&txnRef=..&ApprovalRefNo=.." (key case varies by app)
  foreach kv in array string_to_array(p_response, '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;

  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;

  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '30 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;

  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- Admin: take back an approved payment whose money never arrived.
create or replace function public.revoke_payment(p_payment_id uuid, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay  public.payments;
  plan public.subscription_plans;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if coalesce(length(trim(p_note)), 0) < 3 then raise exception 'Please type a short reason'; end if;
  update public.payments
     set status = 'revoked', admin_note = trim(p_note), reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'approved'
  returning * into pay;
  if not found then raise exception 'Only approved payments can be revoked'; end if;

  select * into plan from public.subscription_plans where id = pay.plan_id;
  update public.subscriptions
     set expires_at = expires_at - make_interval(days => plan.duration_days), updated_at = now()
   where user_id = pay.user_id;
  update public.subscriptions
     set status = 'expired', updated_at = now()
   where user_id = pay.user_id and status = 'active' and expires_at <= now();
  return pay;
end $$;

-- Abandoned attempts (the customer backed out of the UPI app) are cleaned up by the sweep.
create or replace function public.expire_subscriptions()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.subscriptions set status = 'expired', updated_at = now()
  where status = 'active' and expires_at <= now();
  get diagnostics n = row_count;
  delete from public.payments where status = 'initiated' and created_at < now() - interval '1 day';
  return n;
end $$;

revoke execute on function public.grant_plan_for_payment(public.payments) from public, anon, authenticated;
revoke execute on function public.start_upi_payment(uuid) from public, anon;
revoke execute on function public.confirm_upi_payment(uuid, text) from public, anon;
revoke execute on function public.revoke_payment(uuid, text) from public, anon;
grant execute on function public.start_upi_payment(uuid) to authenticated;
grant execute on function public.confirm_upi_payment(uuid, text) to authenticated;
grant execute on function public.revoke_payment(uuid, text) to authenticated;
revoke execute on function public.expire_subscriptions() from public, anon, authenticated;

-- A UTR that was already confirmed automatically can't be claimed again by hand.
create or replace function public.submit_payment(p_plan_id uuid, p_reference text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  r   public.payments;
  ref text := upper(regexp_replace(p_reference, '\s', '', 'g'));
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if exists (select 1 from public.payments where user_id = auth.uid() and status = 'pending') then
    raise exception 'You already have a payment waiting for verification';
  end if;
  if exists (select 1 from public.payments where reference = ref or upper(txn_id) = ref) then
    raise exception 'This reference number has already been submitted';
  end if;
  insert into public.payments (plan_id, reference) values (p_plan_id, p_reference) returning * into r;
  return r;
end $$;

-- ---------------------------------------------------------------------
-- 15. HOURLY PLANS — a plan lasts duration_days + duration_hours.
-- ---------------------------------------------------------------------
alter table public.subscription_plans add column if not exists duration_hours int not null default 0;
alter table public.subscription_plans drop constraint if exists subscription_plans_duration_days_check;
alter table public.subscription_plans drop constraint if exists subscription_plans_duration_check;
alter table public.subscription_plans add constraint subscription_plans_duration_check
  check (duration_days >= 0 and duration_hours >= 0 and (duration_days > 0 or duration_hours > 0));

insert into public.subscription_plans (name, duration_label, duration_days, duration_hours, price, sort_order)
select '1 Hour Pass', '1 Hour', 0, 1, 1, 0
where not exists (select 1 from public.subscription_plans where name = '1 Hour Pass');

create or replace function public.plan_interval(plan public.subscription_plans)
returns interval language sql immutable as $$
  select make_interval(days => plan.duration_days, hours => plan.duration_hours)
$$;

-- new_expiry = max(now, current active expiry) + plan length (keeps unused time)
create or replace function public.grant_plan_for_payment(pay public.payments)
returns void language plpgsql security definer set search_path = public as $$
declare
  plan public.subscription_plans;
  cur  public.subscriptions;
  base timestamptz;
begin
  select * into plan from public.subscription_plans where id = pay.plan_id;
  select * into cur  from public.subscriptions where user_id = pay.user_id for update;
  base := greatest(now(), coalesce(case when cur.status = 'active' then cur.expires_at end, now()));
  insert into public.subscriptions (user_id, user_email, plan_id, status, expires_at, updated_at)
  values (pay.user_id, pay.user_email, pay.plan_id, 'active', base + public.plan_interval(plan), now())
  on conflict (user_id) do update
     set plan_id = excluded.plan_id, user_email = excluded.user_email, status = 'active',
         expires_at = excluded.expires_at, updated_at = now();
end $$;

create or replace function public.revoke_payment(p_payment_id uuid, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay  public.payments;
  plan public.subscription_plans;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if coalesce(length(trim(p_note)), 0) < 3 then raise exception 'Please type a short reason'; end if;
  update public.payments
     set status = 'revoked', admin_note = trim(p_note), reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'approved'
  returning * into pay;
  if not found then raise exception 'Only approved payments can be revoked'; end if;
  select * into plan from public.subscription_plans where id = pay.plan_id;
  update public.subscriptions
     set expires_at = expires_at - public.plan_interval(plan), updated_at = now()
   where user_id = pay.user_id;
  update public.subscriptions
     set status = 'expired', updated_at = now()
   where user_id = pay.user_id and status = 'active' and expires_at <= now();
  return pay;
end $$;

revoke execute on function public.grant_plan_for_payment(public.payments) from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- 16. BANK-CREDIT AUTO VERIFICATION (no gateway)
--     GPay/PhonePe usually return nothing to the app for payments to a personal
--     UPI ID, so the app can't learn the payment happened. Instead:
--       * every order gets a unique amount (plan price + 1..99 paise), valid 60 min;
--       * the owner's phone (Streams app, signed in as admin, "Auto-verify" on)
--         reads the bank's "credited" SMS / UPI app notification and calls
--         record_bank_credit(amount, ref);
--       * the server finds the open order with exactly that amount and activates it.
--     The customer's app polls its order and shows success as soon as it flips.
-- ---------------------------------------------------------------------
create table if not exists public.bank_credits (
  id          uuid primary key default gen_random_uuid(),
  amount      numeric(10,2) not null,
  ref         text,
  source      text,
  raw         text,
  payment_id  uuid references public.payments(id) on delete set null,
  received_at timestamptz not null default now(),
  reported_by text
);
create unique index if not exists bank_credits_ref_unique on public.bank_credits (ref) where ref is not null;
alter table public.bank_credits enable row level security;
drop policy if exists "admins read bank credits" on public.bank_credits;
create policy "admins read bank credits" on public.bank_credits for select using (public.is_admin());

alter table public.payments add column if not exists bank_credit_id uuid references public.bank_credits(id) on delete set null;

-- Step 1: order with a unique amount so an incoming credit identifies it.
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  r     public.payments;
  price numeric(10,2);
  amt   numeric(10,2);
  tries int := 0;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  delete from public.payments where user_id = auth.uid() and status = 'initiated';
  select p.price into price from public.subscription_plans p where p.id = p_plan_id and p.active;
  if not found then raise exception 'This plan is not available'; end if;
  loop
    tries := tries + 1;
    amt := price + (1 + floor(random() * 99))::int / 100.0;
    -- Unique among open orders, and among auto orders still waiting for their bank credit.
    exit when not exists (
      select 1 from public.payments
       where amount = amt
         and ((status = 'initiated' and created_at > now() - interval '60 minutes')
           or (status = 'approved' and method = 'upi_auto' and bank_credit_id is null
               and created_at > now() - interval '120 minutes')));
    if tries > 200 then raise exception 'Too many payments in progress, please try again in a minute'; end if;
  end loop;
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id, 'STR' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14)), 'initiated')
  returning * into r;
  update public.payments set amount = amt where id = r.id returning * into r;
  return r;
end $$;

-- Step 2 (owner's phone): a credit arrived. Match it to the open order with that amount.
create or replace function public.record_bank_credit(p_amount numeric, p_ref text, p_source text, p_raw text)
returns json language plpgsql security definer set search_path = public as $$
declare
  c    public.bank_credits;
  pay  public.payments;
  v_ref text := nullif(upper(regexp_replace(coalesce(p_ref, ''), '\s', '', 'g')), '');
  amt  numeric(10,2) := round(p_amount, 2);
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if amt is null or amt <= 0 then raise exception 'Bad amount'; end if;

  -- The same credit often arrives twice (bank SMS + UPI app notification).
  select * into c from public.bank_credits
   where (v_ref is not null and bank_credits.ref = v_ref)
      or (bank_credits.amount = amt and bank_credits.received_at > now() - interval '5 minutes'
          and (v_ref is null or bank_credits.ref is null))
   order by received_at desc limit 1 for update;
  if found then
    if c.ref is null and v_ref is not null then
      update public.bank_credits set ref = v_ref where id = c.id returning * into c;
      update public.payments set txn_id = coalesce(txn_id, v_ref) where id = c.payment_id
        and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref);
    end if;
    return json_build_object('duplicate', true, 'payment_id', c.payment_id);
  end if;

  insert into public.bank_credits (amount, ref, source, raw, reported_by)
  values (amt, v_ref, left(p_source, 80), left(p_raw, 500), auth.jwt() ->> 'email')
  returning * into c;

  -- Open order with exactly this amount → activate it.
  update public.payments
     set status = 'approved', method = 'upi_auto', bank_credit_id = c.id,
         txn_id = case when v_ref is not null and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref) then v_ref end,
         reviewed_at = now(), reviewed_by = 'auto (bank credit)'
   where id = (select id from public.payments
                where status = 'initiated' and amount = amt and created_at > now() - interval '60 minutes'
                order by created_at desc limit 1 for update skip locked)
  returning * into pay;
  if found then
    perform public.grant_plan_for_payment(pay);
  else
    -- Already activated from the UPI app's own response? Mark it bank-verified.
    update public.payments set bank_credit_id = c.id
     where id = (select id from public.payments
                  where status = 'approved' and method = 'upi_auto' and bank_credit_id is null
                    and amount = amt and created_at > now() - interval '120 minutes'
                  order by created_at desc limit 1)
    returning * into pay;
  end if;
  if pay.id is not null then update public.bank_credits set payment_id = pay.id where id = c.id; end if;
  return json_build_object('duplicate', false, 'payment_id', pay.id, 'plan', pay.plan_name, 'email', pay.user_email);
end $$;

-- Confirmation from the UPI app's response (when an app does send one): 60-minute window.
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  kv     text;
  f      jsonb := '{}';
  st     text;
  txn    text;
  pay    public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;
  foreach kv in array string_to_array(p_response, '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;
  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '60 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

revoke execute on function public.record_bank_credit(numeric, text, text, text) from public, anon;
grant execute on function public.record_bank_credit(numeric, text, text, text) to authenticated;

-- ---------------------------------------------------------------------
-- 17. EXACT PLAN PRICE (no extra paise). Orders are charged exactly the plan
--     price; an incoming credit activates the most recent open order with that
--     amount from the last 20 minutes. If two customers buy the same plan in the
--     same few minutes, each credit activates one of them (the credit is marked
--     ambiguous so the owner can double-check); anyone left waiting can still
--     submit their UTR.
-- ---------------------------------------------------------------------
alter table public.bank_credits add column if not exists ambiguous boolean not null default false;

create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  delete from public.payments where user_id = auth.uid() and status = 'initiated';
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id, 'STR' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14)), 'initiated')
  returning * into r;   -- amount = plan price (set by payments_before_insert)
  return r;
end $$;

create or replace function public.record_bank_credit(p_amount numeric, p_ref text, p_source text, p_raw text)
returns json language plpgsql security definer set search_path = public as $$
declare
  c     public.bank_credits;
  pay   public.payments;
  v_ref text := nullif(upper(regexp_replace(coalesce(p_ref, ''), '\s', '', 'g')), '');
  amt   numeric(10,2) := round(p_amount, 2);
  open_count int;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if amt is null or amt <= 0 then raise exception 'Bad amount'; end if;

  -- The same credit often arrives twice (bank SMS + UPI app notification).
  select * into c from public.bank_credits
   where (v_ref is not null and bank_credits.ref = v_ref)
      -- same money reported once by SMS and once by an app notification (never two SMS / two alerts)
      or (bank_credits.amount = amt and bank_credits.received_at > now() - interval '3 minutes'
          and (v_ref is null or bank_credits.ref is null)
          and split_part(bank_credits.source, ':', 1) <> split_part(coalesce(p_source, ''), ':', 1))
   order by received_at desc limit 1 for update;
  if found then
    if c.ref is null and v_ref is not null then
      update public.bank_credits set ref = v_ref where id = c.id returning * into c;
      update public.payments set txn_id = coalesce(txn_id, v_ref) where id = c.payment_id
        and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref);
    end if;
    return json_build_object('duplicate', true, 'payment_id', c.payment_id);
  end if;

  select count(*) into open_count from public.payments
   where status = 'initiated' and amount = amt and created_at > now() - interval '20 minutes';

  insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
  values (amt, v_ref, left(p_source, 80), left(p_raw, 500), auth.jwt() ->> 'email', open_count > 1)
  returning * into c;

  update public.payments
     set status = 'approved', method = 'upi_auto', bank_credit_id = c.id,
         txn_id = case when v_ref is not null and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref) then v_ref end,
         reviewed_at = now(), reviewed_by = 'auto (bank credit)'
   where id = (select id from public.payments
                where status = 'initiated' and amount = amt and created_at > now() - interval '20 minutes'
                order by created_at desc limit 1 for update skip locked)
  returning * into pay;
  if found then
    perform public.grant_plan_for_payment(pay);
  else
    -- Already activated from the UPI app's own response? Mark it bank-verified.
    update public.payments set bank_credit_id = c.id
     where id = (select id from public.payments
                  where status = 'approved' and method = 'upi_auto' and bank_credit_id is null
                    and amount = amt and created_at > now() - interval '60 minutes'
                  order by created_at desc limit 1)
    returning * into pay;
  end if;
  if pay.id is not null then update public.bank_credits set payment_id = pay.id where id = c.id; end if;
  return json_build_object('duplicate', false, 'payment_id', pay.id, 'plan', pay.plan_name,
                           'email', pay.user_email, 'ambiguous', open_count > 1);
end $$;

-- Orders stay open 20 minutes (matches the bank-credit window).
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  kv     text;
  f      jsonb := '{}';
  st     text;
  txn    text;
  pay    public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;
  foreach kv in array string_to_array(p_response, '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;
  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '20 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- ---------------------------------------------------------------------
-- 18. MERCHANT UPI + FULL UPI APP RESULT
--     The UPI link carries tr (order number) and, if set, mc (merchant category
--     code). Whatever the UPI app returns - success, failure, pending - is saved.
-- ---------------------------------------------------------------------
alter table public.app_settings add column if not exists merchant_code text;

alter table public.payments drop constraint if exists payments_status_check;
alter table public.payments add constraint payments_status_check
  check (status in ('initiated', 'pending', 'approved', 'rejected', 'revoked', 'failed'));

-- Turns the UPI app's "k=v&k=v" response into json with lower-case keys.
create or replace function public.parse_upi_response(p_response text)
returns jsonb language plpgsql immutable as $$
declare kv text; f jsonb := '{}';
begin
  foreach kv in array string_to_array(coalesce(p_response, ''), '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;
  return f;
end $$;

-- SUCCESS → plan applied now. Also checks the response belongs to this order (txnRef).
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  f   jsonb;
  st  text;
  txn text;
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;
  f  := public.parse_upi_response(p_response);
  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  if nullif(f ->> 'txnref', '') is not null and exists (
       select 1 from public.payments where id = p_payment_id and upper(reference) <> upper(f ->> 'txnref')) then
    raise exception 'This payment response belongs to a different order';
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '20 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- Whatever the UPI app returned (success, failure, pending) is saved on the order.
--   SUCCESS          → approved, plan applied
--   FAILURE          → failed (shown to the customer and in admin)
--   SUBMITTED/other  → stays open; a bank credit can still activate it
create or replace function public.report_upi_result(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  st  text := lower(coalesce(public.parse_upi_response(p_response) ->> 'status', ''));
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if st = 'success' then
    return public.confirm_upi_payment(p_payment_id, p_response);
  end if;
  update public.payments
     set upi_response = left(p_response, 2000),
         status       = case when st in ('failure', 'failed') then 'failed' else status end,
         reviewed_at  = case when st in ('failure', 'failed') then now() else reviewed_at end,
         reviewed_by  = case when st in ('failure', 'failed') then 'UPI app: payment failed' else reviewed_by end
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then select * into pay from public.payments where id = p_payment_id and user_id = auth.uid(); end if;
  return pay;
end $$;

revoke execute on function public.report_upi_result(uuid, text) from public, anon;
grant execute on function public.report_upi_result(uuid, text) to authenticated;
revoke execute on function public.parse_upi_response(text) from public, anon;
-- ---------------------------------------------------------------------
-- 19. ACQUISITION ATTRIBUTION, STREAMS_SPECIAL CONTENT, PURCHASE ATTRIBUTION
--     and ANALYTICS EVENTS
--
--   Install (device)  → attribution_installs   one row per app install, written from the
--                                              Google Play Install Referrer (record_install)
--   Account (user)    → user_attribution       first touch (never overwritten) + last touch,
--                                              linked on sign-in (attribute_user)
--   Content           → titles.tags            STREAMS_SPECIAL titles are only returned by
--                                              the database to Meta-acquired accounts
--   Purchases         → payments.attribution_* snapshot of the buyer's first-touch source,
--                                              taken when a payment is verified/approved
--   Events            → analytics_events       written only through log_event / triggers
--
--   Nobody but admins can read these tables, and nobody can write them directly:
--   all writes go through the SECURITY DEFINER functions below, which parse and
--   validate the referrer on the server.
-- ---------------------------------------------------------------------

-- ---------- helpers ----------------------------------------------------
create or replace function public.url_decode(p text)
returns text language plpgsql immutable as $$
declare r bytea := ''; i int := 1; n int; c text;
begin
  if p is null then return null; end if;
  n := length(p);
  while i <= n loop
    c := substr(p, i, 1);
    if c = '%' and i + 2 <= n and substr(p, i + 1, 2) ~ '^[0-9A-Fa-f]{2}$' then
      r := r || decode(substr(p, i + 1, 2), 'hex'); i := i + 3;
    elsif c = '+' then
      r := r || '\x20'::bytea; i := i + 1;
    else
      r := r || convert_to(c, 'UTF8'); i := i + 1;
    end if;
  end loop;
  return convert_from(r, 'UTF8');
exception when others then
  return p;
end $$;

-- Keep campaign/ad names readable but harmless.
create or replace function public.clean_utm(p text)
returns text language sql immutable as $$
  select nullif(left(trim(regexp_replace(coalesce(p, ''), '[^A-Za-z0-9 _.:|()-]', '', 'g')), 100), '')
$$;

-- Play Install Referrer string → {source, medium, campaign, content, term}.
--   source is one of: meta | organic | direct | other | unknown
create or replace function public.parse_install_referrer(p_referrer text)
returns jsonb language plpgsql immutable as $$
declare
  ref text := trim(coalesce(p_referrer, ''));
  kv  text;
  f   jsonb := '{}';
  s   text; m text; camp text; cont text; term text;
  src text;
begin
  if ref = '' then
    return jsonb_build_object('source', 'unknown');
  end if;
  -- Some links encode the whole referrer once more ("utm_source%3Dmeta%26...").
  if position('=' in ref) = 0 and ref ilike '%\%3D%' then ref := public.url_decode(ref); end if;
  foreach kv in array string_to_array(left(ref, 1000), '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))),
                                   public.url_decode(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;

  s    := lower(coalesce(f ->> 'utm_source', ''));
  m    := public.clean_utm(f ->> 'utm_medium');
  camp := public.clean_utm(f ->> 'utm_campaign');
  term := public.clean_utm(f ->> 'utm_term');
  -- Meta's own app-install ads put an encrypted JSON blob in utm_content; never store it as a name.
  cont := case when left(trim(coalesce(f ->> 'utm_content', '')), 1) = '{' then null
               else public.clean_utm(f ->> 'utm_content') end;

  src := case
    when s in ('meta', 'facebook', 'fb', 'instagram', 'ig', 'an', 'audience_network', 'messenger', 'threads')
      or s like '%facebook.com%' or s like '%instagram.com%'                      then 'meta'
    when s = 'google-play' and lower(coalesce(f ->> 'utm_medium', '')) = 'organic' then 'organic'
    when s in ('', '(not set)', '(not%20set)', '(direct)', 'direct')                then 'direct'
    else 'other'
  end;
  -- Meta's generic app-install campaign label is not a campaign name.
  if src = 'meta' and lower(coalesce(camp, '')) in ('fb4a', 'ig4a') then camp := null; end if;

  return jsonb_build_object('source', src, 'medium', m, 'campaign', camp, 'content', cont, 'term', term,
                            'utm_source', public.clean_utm(f ->> 'utm_source'));
end $$;

-- ---------- tables -----------------------------------------------------
create table if not exists public.attribution_installs (
  install_id        uuid primary key,                 -- random id the app creates once per install
  referrer_status   text not null check (referrer_status in ('ok', 'not_supported', 'unavailable', 'error')),
  install_referrer  text,                             -- raw Play referrer (max 1000 chars)
  source            text not null default 'unknown' check (source in ('meta', 'organic', 'direct', 'other', 'unknown')),
  utm_source        text,
  medium            text,
  campaign          text,
  content           text,
  term              text,
  referrer_click_at timestamptz,
  install_begin_at  timestamptz,
  first_user_id     uuid references auth.users(id) on delete set null,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index if not exists attribution_installs_source_idx   on public.attribution_installs (source, created_at);
create index if not exists attribution_installs_campaign_idx on public.attribution_installs (campaign);
alter table public.attribution_installs enable row level security;
drop policy if exists "admins read installs" on public.attribution_installs;
create policy "admins read installs" on public.attribution_installs for select using (public.is_admin());

create table if not exists public.user_attribution (
  user_id               uuid primary key references auth.users(id) on delete cascade,
  first_touch_source    text not null default 'unknown' check (first_touch_source in ('meta', 'organic', 'direct', 'other', 'unknown')),
  first_touch_medium    text,
  first_touch_campaign  text,
  first_touch_content   text,
  first_touch_term      text,
  first_touch_at        timestamptz,
  first_install_id      uuid references public.attribution_installs(install_id) on delete set null,
  last_touch_source     text check (last_touch_source in ('meta', 'organic', 'direct', 'other', 'unknown')),
  last_touch_medium     text,
  last_touch_campaign   text,
  last_touch_content    text,
  last_touch_term       text,
  last_touch_at         timestamptz,
  last_install_id       uuid references public.attribution_installs(install_id) on delete set null,
  install_referrer      text,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
create index if not exists user_attribution_source_idx   on public.user_attribution (first_touch_source);
create index if not exists user_attribution_campaign_idx on public.user_attribution (first_touch_campaign);
alter table public.user_attribution enable row level security;
drop policy if exists "admins read user attribution" on public.user_attribution;
create policy "admins read user attribution" on public.user_attribution for select using (public.is_admin());
-- No insert/update/delete policies: the app can never write its own attribution.

create table if not exists public.analytics_events (
  id          bigint generated always as identity primary key,
  event       text not null check (event in ('app_open', 'install_attributed', 'signup', 'login', 'content_view',
                                             'special_content_view', 'subscription_started', 'payment_success',
                                             'subscription_expired')),
  user_id     uuid references auth.users(id) on delete set null,
  install_id  uuid,
  source      text,
  campaign    text,
  content_id  uuid references public.titles(id) on delete set null,
  plan_id     uuid references public.subscription_plans(id) on delete set null,
  created_at  timestamptz not null default now()
);
create index if not exists analytics_events_event_idx on public.analytics_events (event, created_at);
create index if not exists analytics_events_user_idx  on public.analytics_events (user_id, created_at);
alter table public.analytics_events enable row level security;
drop policy if exists "admins read events" on public.analytics_events;
create policy "admins read events" on public.analytics_events for select using (public.is_admin());

-- Purchase attribution snapshot (filled when a payment is approved; never changes the user's source).
alter table public.payments add column if not exists attribution_source   text;
alter table public.payments add column if not exists attribution_campaign text;
alter table public.payments add column if not exists attribution_content  text;

-- Content tags. STREAMS_SPECIAL = campaign content for Meta-acquired accounts.
alter table public.titles add column if not exists tags text[] not null default '{}';
alter table public.titles drop constraint if exists titles_tags_check;
alter table public.titles add constraint titles_tags_check check (tags <@ array['STREAMS_SPECIAL', 'NEW']::text[]);
create index if not exists titles_tags_idx on public.titles using gin (tags);

-- ---------- who is Meta-acquired (server-side, authoritative) ----------
create or replace function public.is_meta_user()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.user_attribution
                  where user_id = auth.uid() and first_touch_source = 'meta')
$$;

-- STREAMS_SPECIAL titles are returned only to Meta-acquired accounts (and admins).
-- Every other visibility rule (drafts, hidden premium) is unchanged.
drop policy if exists "visible titles" on public.titles;
create policy "visible titles" on public.titles for select using (
  public.is_admin()
  or (published
      and (tier <> 'hidden_premium' or public.has_active_subscription())
      and (not ('STREAMS_SPECIAL' = any (tags)) or public.is_meta_user()))
);

-- Same rule for the video files themselves (premium/free rules unchanged).
create or replace function public.can_stream(object_name text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare t public.titles;
begin
  if public.is_admin() then return true; end if;
  if auth.uid() is null then return false; end if;

  select * into t from public.titles
   where published and (trailer_path = object_name or video_path = object_name)
   limit 1;
  if not found then
    select tt.* into t from public.episodes e join public.titles tt on tt.id = e.title_id
     where tt.published and e.video_path = object_name limit 1;
    if not found then return false; end if;
  end if;

  if 'STREAMS_SPECIAL' = any (t.tags) and not public.is_meta_user() then return false; end if;
  if t.tier = 'hidden_premium' then return public.has_active_subscription(); end if;
  if t.trailer_path = object_name then return true; end if;
  if t.tier = 'free' then return true; end if;
  return public.has_active_subscription() or t.trailer_path is null;
end $$;

-- ---------- 1) app reports its install (signed in or not) ---------------
create or replace function public.record_install(
  p_install_id uuid, p_status text, p_referrer text, p_click_ts bigint, p_install_ts bigint)
returns json language plpgsql security definer set search_path = public as $$
declare
  p   jsonb;
  ins public.attribution_installs;
  st  text := lower(coalesce(p_status, ''));
  ref text := nullif(left(trim(coalesce(p_referrer, '')), 1000), '');
begin
  if p_install_id is null then raise exception 'Missing install id'; end if;
  if st not in ('ok', 'not_supported', 'unavailable', 'error') then raise exception 'Bad referrer status'; end if;
  if st <> 'ok' then ref := null; end if;
  p := public.parse_install_referrer(ref);

  insert into public.attribution_installs as a
    (install_id, referrer_status, install_referrer, source, utm_source, medium, campaign, content, term,
     referrer_click_at, install_begin_at)
  values
    (p_install_id, st, ref, p ->> 'source', p ->> 'utm_source', p ->> 'medium', p ->> 'campaign',
     p ->> 'content', p ->> 'term',
     case when p_click_ts   > 0 and p_click_ts   < 4102444800 then to_timestamp(p_click_ts)   end,
     case when p_install_ts > 0 and p_install_ts < 4102444800 then to_timestamp(p_install_ts) end)
  on conflict (install_id) do update
     -- First successful read wins; only a missing/failed read may be filled in later.
     set referrer_status = excluded.referrer_status, install_referrer = excluded.install_referrer,
         source = excluded.source, utm_source = excluded.utm_source, medium = excluded.medium,
         campaign = excluded.campaign, content = excluded.content, term = excluded.term,
         referrer_click_at = excluded.referrer_click_at, install_begin_at = excluded.install_begin_at,
         updated_at = now()
   where a.referrer_status <> 'ok' and excluded.referrer_status = 'ok'
  returning * into ins;

  if ins.install_id is null then select * into ins from public.attribution_installs where install_id = p_install_id; end if;
  return json_build_object('source', ins.source, 'status', ins.referrer_status);
end $$;

-- ---------- 2) signed-in account claims the install ---------------------
--   First touch is set once and never overwritten. An install only becomes an
--   account's first touch if that account was created on/after the install and no
--   other account has already claimed it — so a different person signing in on the
--   same phone later is not counted as Meta-acquired.
create or replace function public.attribute_user(p_install_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare
  uid      uuid := auth.uid();
  ins      public.attribution_installs;
  ua       public.user_attribution;
  acct_at  timestamptz;
  eligible boolean := false;
  touch_at timestamptz;
begin
  if uid is null then raise exception 'Please sign in first'; end if;
  select * into ins from public.attribution_installs where install_id = p_install_id for update;
  if not found then return json_build_object('pending', true); end if;

  select created_at into acct_at from auth.users where id = uid;
  touch_at := coalesce(ins.referrer_click_at, ins.install_begin_at, ins.created_at);
  eligible := ins.referrer_status = 'ok'
          and (ins.first_user_id is null or ins.first_user_id = uid)
          and acct_at >= coalesce(ins.install_begin_at, ins.created_at) - interval '1 day';

  if eligible and ins.first_user_id is null then
    update public.attribution_installs set first_user_id = uid, updated_at = now() where install_id = ins.install_id;
  end if;

  select * into ua from public.user_attribution where user_id = uid for update;
  if not found then
    insert into public.user_attribution (
      user_id, first_touch_source, first_touch_medium, first_touch_campaign, first_touch_content, first_touch_term,
      first_touch_at, first_install_id,
      last_touch_source, last_touch_medium, last_touch_campaign, last_touch_content, last_touch_term,
      last_touch_at, last_install_id, install_referrer)
    values (
      uid,
      case when eligible then ins.source else 'unknown' end,
      case when eligible then ins.medium end,
      case when eligible then ins.campaign end,
      case when eligible then ins.content end,
      case when eligible then ins.term end,
      case when eligible then touch_at else acct_at end,
      case when eligible then ins.install_id end,
      ins.source, ins.medium, ins.campaign, ins.content, ins.term, touch_at, ins.install_id, ins.install_referrer)
    returning * into ua;
    if eligible then
      insert into public.analytics_events (event, user_id, install_id, source, campaign)
      values ('install_attributed', uid, ins.install_id, ins.source, ins.campaign);
    end if;
  else
    -- Never overwrite a known first touch; only fill one that was never set.
    if ua.first_install_id is null and ua.first_touch_source = 'unknown' and eligible then
      update public.user_attribution
         set first_touch_source = ins.source, first_touch_medium = ins.medium, first_touch_campaign = ins.campaign,
             first_touch_content = ins.content, first_touch_term = ins.term, first_touch_at = touch_at,
             first_install_id = ins.install_id, updated_at = now()
       where user_id = uid;
      insert into public.analytics_events (event, user_id, install_id, source, campaign)
      values ('install_attributed', uid, ins.install_id, ins.source, ins.campaign);
    end if;
    -- Last touch follows the most recent install this account was used on (no-op if unchanged).
    if ua.last_install_id is distinct from ins.install_id and ins.referrer_status = 'ok' then
      update public.user_attribution
         set last_touch_source = ins.source, last_touch_medium = ins.medium, last_touch_campaign = ins.campaign,
             last_touch_content = ins.content, last_touch_term = ins.term, last_touch_at = touch_at,
             last_install_id = ins.install_id, install_referrer = ins.install_referrer, updated_at = now()
       where user_id = uid;
    end if;
    select * into ua from public.user_attribution where user_id = uid;
  end if;

  return json_build_object('source', ua.first_touch_source, 'special', ua.first_touch_source = 'meta');
end $$;

-- ---------- 3) app events (no personal data; source added server-side) --
create or replace function public.log_event(p_event text, p_install_id uuid, p_content_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  uid  uuid := auth.uid();
  src  text;
  camp text;
  special boolean := false;
begin
  if p_event not in ('app_open', 'login', 'content_view') then raise exception 'Unsupported event'; end if;
  if p_event = 'login' and uid is null then return; end if;
  if uid is null and p_install_id is null then return; end if;
  -- Cheap de-duplication of repeated taps / double opens.
  if exists (select 1 from public.analytics_events
              where event in (p_event, case when p_event = 'content_view' then 'special_content_view' end)
                and created_at > now() - interval '30 seconds'
                and (user_id = uid or (uid is null and install_id = p_install_id))
                and content_id is not distinct from p_content_id) then
    return;
  end if;

  if uid is not null then
    select first_touch_source, first_touch_campaign into src, camp from public.user_attribution where user_id = uid;
  end if;
  if src is null and p_install_id is not null then
    select source, campaign into src, camp from public.attribution_installs where install_id = p_install_id;
  end if;
  if p_content_id is not null then
    select 'STREAMS_SPECIAL' = any (tags) into special from public.titles where id = p_content_id;
    if not found then return; end if;
  end if;

  insert into public.analytics_events (event, user_id, install_id, source, campaign, content_id)
  values (case when p_event = 'content_view' and coalesce(special, false) then 'special_content_view' else p_event end,
          uid, p_install_id, coalesce(src, 'unknown'), camp, p_content_id);
end $$;

-- ---------- 4) server-side events --------------------------------------
-- signup: every new account.
create or replace function public.on_auth_user_created()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.analytics_events (event, user_id, source) values ('signup', new.id, 'unknown');
  return new;
exception when others then
  return new;   -- analytics must never block a sign-up
end $$;
drop trigger if exists streams_on_auth_user_created on auth.users;
create trigger streams_on_auth_user_created after insert on auth.users
  for each row execute function public.on_auth_user_created();

-- Purchase attribution: when a payment becomes approved (manual approval, UPI app
-- success or bank-credit match — all verified server-side), snapshot the buyer's
-- first-touch source onto the payment and log payment_success / subscription_started.
create or replace function public.payments_attribution()
returns trigger language plpgsql security definer set search_path = public as $$
declare ua public.user_attribution; had_active boolean;
begin
  if new.status = 'approved' and old.status is distinct from 'approved' then
    select * into ua from public.user_attribution where user_id = new.user_id;
    new.attribution_source   := coalesce(ua.first_touch_source, 'unknown');
    new.attribution_campaign := ua.first_touch_campaign;
    new.attribution_content  := ua.first_touch_content;

    select exists (select 1 from public.subscriptions
                    where user_id = new.user_id and status = 'active' and expires_at > now()) into had_active;
    insert into public.analytics_events (event, user_id, source, campaign, plan_id)
    values ('payment_success', new.user_id, new.attribution_source, new.attribution_campaign, new.plan_id);
    if not had_active then
      insert into public.analytics_events (event, user_id, source, campaign, plan_id)
      values ('subscription_started', new.user_id, new.attribution_source, new.attribution_campaign, new.plan_id);
    end if;
  end if;
  return new;
end $$;
drop trigger if exists payments_attribution on public.payments;
create trigger payments_attribution before update on public.payments
  for each row execute function public.payments_attribution();

-- subscription_expired: logged by the expiry sweep.
create or replace function public.expire_subscriptions()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  with expired as (
    update public.subscriptions set status = 'expired', updated_at = now()
     where status = 'active' and expires_at <= now()
    returning user_id, plan_id
  )
  insert into public.analytics_events (event, user_id, source, campaign, plan_id)
  select 'subscription_expired', e.user_id, coalesce(ua.first_touch_source, 'unknown'), ua.first_touch_campaign, e.plan_id
    from expired e left join public.user_attribution ua on ua.user_id = e.user_id;
  get diagnostics n = row_count;
  delete from public.payments where status = 'initiated' and created_at < now() - interval '1 day';
  return n;
end $$;

-- ---------- 5) admin reporting -----------------------------------------
-- Source buckets and campaign rows for a date range + optional filters.
--   installs       devices that reported an install (Play Install Referrer)
--   registrations  accounts created in the range, by first-touch source
--   purchasers     distinct buyers with a verified (approved) payment in the range
--   revenue        sum of verified payments in the range (revoked/failed excluded)
create or replace function public.admin_attribution_report(
  p_from timestamptz, p_to timestamptz, p_source text, p_campaign text, p_plan uuid)
returns json language plpgsql stable security definer set search_path = public as $$
declare
  f timestamptz := coalesce(p_from, '-infinity');
  t timestamptz := coalesce(p_to, 'infinity');
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  return (
    with
    inst as (
      select source, campaign, count(*) n from public.attribution_installs
       where created_at >= f and created_at < t
         and (p_source is null or source = p_source)
         and (p_campaign is null or campaign = p_campaign)
       group by 1, 2),
    regs as (
      select coalesce(ua.first_touch_source, 'unknown') source, ua.first_touch_campaign campaign, count(*) n
        from auth.users u left join public.user_attribution ua on ua.user_id = u.id
       where u.created_at >= f and u.created_at < t
         and (p_source is null or coalesce(ua.first_touch_source, 'unknown') = p_source)
         and (p_campaign is null or ua.first_touch_campaign = p_campaign)
       group by 1, 2),
    pays as (
      select coalesce(p.attribution_source, ua.first_touch_source, 'unknown') source,
             case when p.attribution_source is not null then p.attribution_campaign else ua.first_touch_campaign end campaign,
             count(distinct p.user_id) buyers, count(*) purchases, coalesce(sum(p.amount), 0) revenue
        from public.payments p left join public.user_attribution ua on ua.user_id = p.user_id
       where p.status = 'approved' and coalesce(p.reviewed_at, p.created_at) >= f and coalesce(p.reviewed_at, p.created_at) < t
         and (p_plan is null or p.plan_id = p_plan)
         and (p_source is null or coalesce(p.attribution_source, ua.first_touch_source, 'unknown') = p_source)
         and (p_campaign is null or coalesce(p.attribution_campaign, ua.first_touch_campaign) = p_campaign)
       group by 1, 2),
    keys as (select source, campaign from inst union select source, campaign from regs union select source, campaign from pays),
    all_rows as (
      select k.source, k.campaign,
             coalesce(i.n, 0) installs, coalesce(r.n, 0) registrations,
             coalesce(p.buyers, 0) purchasers, coalesce(p.purchases, 0) purchases, coalesce(p.revenue, 0) revenue
        from keys k
        left join inst i on i.source = k.source and i.campaign is not distinct from k.campaign
        left join regs r on r.source = k.source and r.campaign is not distinct from k.campaign
        left join pays p on p.source = k.source and p.campaign is not distinct from k.campaign)
    select json_build_object(
      'by_source', coalesce((select json_agg(x order by x.revenue desc, x.registrations desc) from (
          select source, sum(installs) installs, sum(registrations) registrations, sum(purchasers) purchasers,
                 sum(purchases) purchases, sum(revenue) revenue
            from all_rows group by source) x), '[]'),
      'campaigns', coalesce((select json_agg(x order by x.revenue desc, x.registrations desc, x.installs desc) from (
          select * from all_rows where campaign is not null) x), '[]'))
  );
end $$;

-- Users with their attribution, plan and revenue (filters: source, campaign, dates, purchase status, plan, text).
create or replace function public.admin_attribution_users(
  p_from timestamptz, p_to timestamptz, p_source text, p_campaign text,
  p_purchased boolean, p_plan uuid, p_search text, p_limit int)
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  return coalesce((select json_agg(x order by x.registered_at desc) from (
    select u.id user_id, u.email, u.created_at registered_at,
           coalesce(ua.first_touch_source, 'unknown') source, ua.first_touch_campaign campaign,
           ua.first_touch_content content, ua.first_touch_at, ua.last_touch_source, ua.last_touch_at,
           s.status sub_status, s.expires_at, sp.name plan_name,
           coalesce(pay.revenue, 0) revenue, coalesce(pay.purchases, 0) purchases
      from auth.users u
      left join public.user_attribution ua on ua.user_id = u.id
      left join public.subscriptions s on s.user_id = u.id
      left join public.subscription_plans sp on sp.id = s.plan_id
      left join lateral (
        select sum(amount) revenue, count(*) purchases from public.payments p
         where p.user_id = u.id and p.status = 'approved' and (p_plan is null or p.plan_id = p_plan)) pay on true
     where u.created_at >= coalesce(p_from, '-infinity') and u.created_at < coalesce(p_to, 'infinity')
       and (p_source is null or coalesce(ua.first_touch_source, 'unknown') = p_source)
       and (p_campaign is null or ua.first_touch_campaign = p_campaign)
       and (p_purchased is null or (coalesce(pay.purchases, 0) > 0) = p_purchased)
       and (p_search is null or u.email ilike '%' || p_search || '%')
     order by u.created_at desc
     limit least(greatest(coalesce(p_limit, 100), 1), 500)) x), '[]');
end $$;

-- One user's full picture for the admin user-detail view.
create or replace function public.admin_user_detail(p_user_id uuid)
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  return (
    select json_build_object(
      'user_id', u.id, 'email', u.email, 'registered_at', u.created_at,
      'attribution', (select row_to_json(ua) from public.user_attribution ua where ua.user_id = u.id),
      'subscription', (select json_build_object('status', s.status, 'expires_at', s.expires_at, 'plan', sp.name)
                         from public.subscriptions s left join public.subscription_plans sp on sp.id = s.plan_id
                        where s.user_id = u.id),
      'payments', coalesce((select json_agg(json_build_object(
                     'id', p.id, 'plan', p.plan_name, 'amount', p.amount, 'status', p.status, 'method', p.method,
                     'utr', coalesce(p.txn_id, p.reference), 'created_at', p.created_at, 'reviewed_at', p.reviewed_at,
                     'attribution_source', p.attribution_source, 'attribution_campaign', p.attribution_campaign)
                     order by p.created_at desc)
                   from public.payments p where p.user_id = u.id and p.status <> 'initiated'), '[]'),
      'revenue', (select coalesce(sum(amount), 0) from public.payments where user_id = u.id and status = 'approved'))
    from auth.users u where u.id = p_user_id);
end $$;

-- ---------- grants -----------------------------------------------------
revoke execute on function public.record_install(uuid, text, text, bigint, bigint) from public;
grant  execute on function public.record_install(uuid, text, text, bigint, bigint) to anon, authenticated;
revoke execute on function public.log_event(text, uuid, uuid) from public;
grant  execute on function public.log_event(text, uuid, uuid) to anon, authenticated;
revoke execute on function public.attribute_user(uuid) from public, anon;
grant  execute on function public.attribute_user(uuid) to authenticated;
revoke execute on function public.admin_attribution_report(timestamptz, timestamptz, text, text, uuid) from public, anon;
grant  execute on function public.admin_attribution_report(timestamptz, timestamptz, text, text, uuid) to authenticated;
revoke execute on function public.admin_attribution_users(timestamptz, timestamptz, text, text, boolean, uuid, text, int) from public, anon;
grant  execute on function public.admin_attribution_users(timestamptz, timestamptz, text, text, boolean, uuid, text, int) to authenticated;
revoke execute on function public.admin_user_detail(uuid) from public, anon;
grant  execute on function public.admin_user_detail(uuid) to authenticated;
revoke execute on function public.on_auth_user_created() from public, anon, authenticated;
revoke execute on function public.payments_attribution() from public, anon, authenticated;
revoke execute on function public.expire_subscriptions() from public, anon, authenticated;

-- Pure helpers: pin search_path; is_meta_user is only meaningful when signed in.
alter function public.url_decode(text) set search_path = public;
alter function public.clean_utm(text) set search_path = public;
alter function public.parse_install_referrer(text) set search_path = public;
alter function public.parse_upi_response(text) set search_path = public;
alter function public.plan_interval(public.subscription_plans) set search_path = public;
revoke execute on function public.is_meta_user() from anon;
-- ---------------------------------------------------------------------
-- 20. Customer cancels an unfinished UPI order (UPI app gave no answer and
--     they did not pay). The order is closed as failed and kept for the record.
-- ---------------------------------------------------------------------
create or replace function public.cancel_upi_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments
     set status = 'failed', reviewed_at = now(), reviewed_by = 'cancelled by customer'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then select * into pay from public.payments where id = p_payment_id and user_id = auth.uid(); end if;
  return pay;
end $$;
revoke execute on function public.cancel_upi_payment(uuid) from public, anon;
grant  execute on function public.cancel_upi_payment(uuid) to authenticated;
-- ---------------------------------------------------------------------
-- 21. VERIFIED-ONLY UPI PAYMENTS
--     The UPI app's answer (SUCCESS/FAILURE) is stored as a *client report* only.
--     A subscription is activated ONLY by apply_verified_payment(), which is called
--     by trusted verification sources:
--       * 'bank_credit'  – the bank's credit alert seen on the owner's phone
--                          (Streams Admin → Auto-verify, record_bank_credit)
--       * 'admin'        – an owner/manager approving a UTR after checking the bank
--       * 'provider_api' – reserved for the merchant bank's status API / webhook
--                          (pending integration; only service_role may call it)
--     Order lifecycle shown to people:
--       CREATED/PENDING = 'initiated'   SUCCESS = 'approved'
--       FAILED = 'failed'               CANCELLED = 'cancelled'
-- ---------------------------------------------------------------------
alter table public.subscription_plans add column if not exists code text;
update public.subscription_plans set code = case
    when name = '1 Hour Pass'   then 'TEST_1HOUR'
    when name = 'Trial'         then 'TRIAL'
    when name = 'Silver Plan'   then 'SILVER'
    when name = 'Gold Plan'     then 'GOLD'
    when name = 'Platinum Plan' then 'PLATINUM'
    when name = 'Diamond Plan'  then 'DIAMOND' end
 where code is null;
create unique index if not exists subscription_plans_code_unique on public.subscription_plans (code) where code is not null;

alter table public.payments add column if not exists verified_at         timestamptz;
alter table public.payments add column if not exists verification_source text;
alter table public.payments add column if not exists client_status       text;   -- what the UPI app reported
alter table public.payments add column if not exists updated_at          timestamptz not null default now();
alter table public.payments drop constraint if exists payments_status_check;
alter table public.payments add constraint payments_status_check
  check (status in ('initiated', 'pending', 'approved', 'rejected', 'revoked', 'failed', 'cancelled'));

-- updated_at is set explicitly by every function below that changes a payment.

-- Order numbers like UPI20261001A1B2C3D4 (letters/digits only: every UPI app accepts it as tr).
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if not exists (select 1 from public.subscription_plans where id = p_plan_id and active) then
    raise exception 'This plan is not available';
  end if;
  -- Re-use an open order for the same plan instead of creating a second one.
  select * into r from public.payments
   where user_id = auth.uid() and status = 'initiated' and plan_id = p_plan_id
     and created_at > now() - interval '24 hours'
   order by created_at desc limit 1;
  if found then return r; end if;
  -- Switching plan closes the older open order (kept for the record).
  update public.payments set status = 'cancelled', updated_at = now(), reviewed_at = now(), reviewed_by = 'replaced by a new order'
   where user_id = auth.uid() and status = 'initiated';
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id,
          'UPI' || to_char(now() at time zone 'Asia/Kolkata', 'YYYYMMDD')
                || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 8)),
          'initiated')
  returning * into r;   -- amount = plan price, set by the server (payments_before_insert)
  return r;
end $$;

-- The ONLY place a payment becomes SUCCESS (and a plan is granted). Idempotent.
create or replace function public.apply_verified_payment(
  p_payment_id uuid, p_amount numeric, p_utr text, p_provider_txn text, p_source text, p_bank_credit uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay public.payments;
  utr text := nullif(upper(regexp_replace(coalesce(p_utr, ''), '\s', '', 'g')), '');
  ptx text := nullif(upper(regexp_replace(coalesce(p_provider_txn, ''), '\s', '', 'g')), '');
begin
  if p_source not in ('bank_credit', 'admin', 'provider_api') then raise exception 'Unknown verification source'; end if;
  select * into pay from public.payments where id = p_payment_id for update;
  if not found then raise exception 'Order not found'; end if;
  if pay.status = 'approved' then return pay; end if;                       -- already activated: no-op
  if pay.status not in ('initiated', 'pending', 'failed', 'cancelled') then
    raise exception 'This order can no longer be paid (status %)', pay.status;
  end if;
  if p_amount is null or round(p_amount, 2) <> pay.amount then
    raise exception 'Amount mismatch: paid %, expected %', p_amount, pay.amount;
  end if;
  if coalesce(utr, ptx) is not null and exists (
       select 1 from public.payments where id <> pay.id and upper(txn_id) = coalesce(utr, ptx)) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', txn_id = coalesce(utr, ptx, txn_id), verified_at = now(), updated_at = now(),
         verification_source = p_source, bank_credit_id = coalesce(p_bank_credit, bank_credit_id),
         reviewed_at = now(),
         reviewed_by = case when p_source = 'admin' then coalesce(auth.jwt() ->> 'email', 'admin')
                            else 'verified: ' || p_source end
   where id = pay.id
  returning * into pay;
  perform public.grant_plan_for_payment(pay);   -- server time; extends from current expiry
  return pay;
end $$;

-- Try to verify one order from trusted evidence already on the server:
-- an unclaimed bank credit of exactly the order amount received after the order was created.
create or replace function public.try_verify_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments; c public.bank_credits;
begin
  select * into pay from public.payments where id = p_payment_id;
  if not found or pay.status not in ('initiated', 'failed', 'cancelled') then return pay; end if;
  select * into c from public.bank_credits
   where payment_id is null and amount = pay.amount
     and received_at >= pay.created_at - interval '2 minutes'
     and received_at <= pay.created_at + interval '24 hours'
   order by received_at limit 1 for update skip locked;
  if not found then return pay; end if;
  pay := public.apply_verified_payment(pay.id, c.amount, c.ref, null, 'bank_credit', c.id);
  update public.bank_credits set payment_id = pay.id where id = c.id;
  return pay;
end $$;

-- App: "I'm back from the UPI app" / "Check payment status". Owner only; returns the
-- authoritative order (after trying verification). Never activates anything by itself.
create or replace function public.check_payment_status(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  select * into pay from public.payments where id = p_payment_id and user_id = auth.uid();
  if not found then raise exception 'Order not found'; end if;
  return public.try_verify_payment(pay.id);
end $$;

-- App: what the UPI app said. Stored for the record only — SUCCESS does NOT activate.
--   FAILURE → order failed (nothing to activate; a later verified bank credit still wins)
--   SUCCESS / SUBMITTED / no answer → order stays open (PENDING) until verified
create or replace function public.report_upi_result(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  st  text := lower(coalesce(public.parse_upi_response(p_response) ->> 'status', ''));
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments
     set upi_response  = left(p_response, 2000),
         updated_at    = now(),
         client_status = nullif(st, ''),
         status        = case when st in ('failure', 'failed') then 'failed' else status end,
         reviewed_at   = case when st in ('failure', 'failed') then now() else reviewed_at end,
         reviewed_by   = case when st in ('failure', 'failed') then 'UPI app: payment failed' else reviewed_by end
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then
    select * into pay from public.payments where id = p_payment_id and user_id = auth.uid();
    if not found then raise exception 'Order not found'; end if;
    return pay;
  end if;
  return public.try_verify_payment(pay.id);
end $$;

-- Older app versions call this after a SUCCESS answer: now it only records it.
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
begin
  return public.report_upi_result(p_payment_id, p_response);
end $$;

create or replace function public.cancel_upi_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments
     set status = 'cancelled', updated_at = now(), reviewed_at = now(), reviewed_by = 'cancelled by customer'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then select * into pay from public.payments where id = p_payment_id and user_id = auth.uid(); end if;
  return pay;
end $$;

-- Admin approves a UTR claim after checking the bank statement (human verification).
create or replace function public.approve_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  select * into pay from public.payments where id = p_payment_id;
  if not found or pay.status <> 'pending' then raise exception 'This payment was already processed'; end if;
  return public.apply_verified_payment(pay.id, pay.amount, pay.reference, null, 'admin', null);
end $$;

-- Owner's phone reports a bank credit → verify the matching open order.
create or replace function public.record_bank_credit(p_amount numeric, p_ref text, p_source text, p_raw text)
returns json language plpgsql security definer set search_path = public as $$
declare
  c     public.bank_credits;
  pay   public.payments;
  v_ref text := nullif(upper(regexp_replace(coalesce(p_ref, ''), '\s', '', 'g')), '');
  amt   numeric(10,2) := round(p_amount, 2);
  open_count int;
  target uuid;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if amt is null or amt <= 0 then raise exception 'Bad amount'; end if;
  select * into c from public.bank_credits
   where (v_ref is not null and bank_credits.ref = v_ref)
      or (bank_credits.amount = amt and bank_credits.received_at > now() - interval '3 minutes'
          and (v_ref is null or bank_credits.ref is null)
          and split_part(bank_credits.source, ':', 1) <> split_part(coalesce(p_source, ''), ':', 1))
   order by received_at desc limit 1 for update;
  if found then
    if c.ref is null and v_ref is not null then
      update public.bank_credits set ref = v_ref where id = c.id returning * into c;
      update public.payments set txn_id = coalesce(txn_id, v_ref), updated_at = now() where id = c.payment_id
        and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref);
    end if;
    return json_build_object('duplicate', true, 'payment_id', c.payment_id);
  end if;
  select count(*) into open_count from public.payments
   where status in ('initiated', 'failed', 'cancelled') and amount = amt and created_at > now() - interval '20 minutes';
  insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
  values (amt, v_ref, left(p_source, 80), left(p_raw, 500), auth.jwt() ->> 'email', open_count > 1)
  returning * into c;
  -- Prefer an open order whose UPI app reported success, then the newest open order.
  select id into target from public.payments
   where status in ('initiated', 'failed', 'cancelled') and amount = amt and created_at > now() - interval '20 minutes'
   order by (client_status = 'success') desc nulls last, (status = 'initiated') desc, created_at desc
   limit 1 for update skip locked;
  if target is not null then
    pay := public.apply_verified_payment(target, amt, v_ref, null, 'bank_credit', c.id);
    update public.bank_credits set payment_id = pay.id where id = c.id;
  end if;
  return json_build_object('duplicate', false, 'payment_id', pay.id, 'plan', pay.plan_name,
                           'email', pay.user_email, 'ambiguous', open_count > 1);
end $$;

-- Expired open orders are closed (kept for the record) instead of deleted.
create or replace function public.expire_subscriptions()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  with expired as (
    update public.subscriptions set status = 'expired', updated_at = now()
     where status = 'active' and expires_at <= now()
    returning user_id, plan_id
  )
  insert into public.analytics_events (event, user_id, source, campaign, plan_id)
  select 'subscription_expired', e.user_id, coalesce(ua.first_touch_source, 'unknown'), ua.first_touch_campaign, e.plan_id
    from expired e left join public.user_attribution ua on ua.user_id = e.user_id;
  get diagnostics n = row_count;
  update public.payments set status = 'cancelled', updated_at = now(), reviewed_at = now(), reviewed_by = 'expired (not paid within 24 h)'
   where status = 'initiated' and created_at < now() - interval '24 hours';
  return n;
end $$;

revoke execute on function public.apply_verified_payment(uuid, numeric, text, text, text, uuid) from public, anon, authenticated;
grant  execute on function public.apply_verified_payment(uuid, numeric, text, text, text, uuid) to service_role;
revoke execute on function public.try_verify_payment(uuid) from public, anon, authenticated;
revoke execute on function public.check_payment_status(uuid) from public, anon;
grant  execute on function public.check_payment_status(uuid) to authenticated;

-- =====================================================================
-- 22. One app: no SMS auto-verify. The admin verifies an open order by
--     matching it with the bank statement and entering that credit's UTR.
-- =====================================================================
create or replace function public.admin_verify_payment(p_payment_id uuid, p_utr text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay public.payments;
  utr text := upper(regexp_replace(coalesce(p_utr, ''), '\s', '', 'g'));
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if utr !~ '^[0-9A-Z]{10,30}$' then raise exception 'Reference number should be the 12-digit UTR from your bank statement'; end if;
  select * into pay from public.payments where id = p_payment_id;
  if not found then raise exception 'Order not found'; end if;
  if pay.status = 'approved' then raise exception 'This payment was already processed'; end if;
  if pay.status not in ('initiated', 'failed', 'cancelled', 'pending') then
    raise exception 'This order can no longer be paid (status %)', pay.status;
  end if;
  return public.apply_verified_payment(pay.id, pay.amount, utr, null, 'admin', null);
end $$;
revoke execute on function public.admin_verify_payment(uuid, text) from public, anon;
grant execute on function public.admin_verify_payment(uuid, text) to authenticated;

-- =====================================================================
-- 23. Back to the v1.1.35 behaviour (owner's decision): the UPI app's
--     SUCCESS answer activates the plan automatically. Guard rails:
--     the answer must name this order (txnRef = order number) when the
--     app echoes it, must carry a transaction ID, that ID works once,
--     the amount comes from the order, and it must arrive within 2 hours.
--     Anything else (no answer, SUBMITTED, PENDING) keeps the order open
--     for "Mark as paid" / a bank status source.
-- =====================================================================
create or replace function public.apply_verified_payment(p_payment_id uuid, p_amount numeric, p_utr text, p_provider_txn text, p_source text, p_bank_credit uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay public.payments;
  utr text := nullif(upper(regexp_replace(coalesce(p_utr, ''), '\s', '', 'g')), '');
  ptx text := nullif(upper(regexp_replace(coalesce(p_provider_txn, ''), '\s', '', 'g')), '');
begin
  if p_source not in ('bank_credit', 'admin', 'provider_api', 'upi_app') then raise exception 'Unknown verification source'; end if;
  select * into pay from public.payments where id = p_payment_id for update;
  if not found then raise exception 'Order not found'; end if;
  if pay.status = 'approved' then return pay; end if;
  if pay.status not in ('initiated', 'pending', 'failed', 'cancelled') then
    raise exception 'This order can no longer be paid (status %)', pay.status;
  end if;
  if p_amount is null or round(p_amount, 2) <> pay.amount then
    raise exception 'Amount mismatch: paid %, expected %', p_amount, pay.amount;
  end if;
  if coalesce(utr, ptx) is not null and exists (
       select 1 from public.payments where id <> pay.id and upper(txn_id) = coalesce(utr, ptx)) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', txn_id = coalesce(utr, ptx, txn_id), verified_at = now(), updated_at = now(),
         verification_source = p_source, bank_credit_id = coalesce(p_bank_credit, bank_credit_id),
         method = case when p_source = 'upi_app' then 'upi_auto' else method end,
         reviewed_at = now(),
         reviewed_by = case when p_source = 'admin' then coalesce(auth.jwt() ->> 'email', 'admin')
                            when p_source = 'upi_app' then 'auto (UPI app)'
                            else 'verified: ' || p_source end
   where id = pay.id
  returning * into pay;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;
revoke execute on function public.apply_verified_payment(uuid, numeric, text, text, text, uuid) from public, anon, authenticated;
grant execute on function public.apply_verified_payment(uuid, numeric, text, text, text, uuid) to service_role;

create or replace function public.report_upi_result(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  f   jsonb := public.parse_upi_response(p_response);
  st  text := lower(coalesce(f ->> 'status', ''));
  txn text;
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments
     set upi_response  = left(p_response, 2000),
         client_status = nullif(st, ''),
         updated_at    = now(),
         status        = case when st in ('failure', 'failed') then 'failed' else status end,
         reviewed_at   = case when st in ('failure', 'failed') then now() else reviewed_at end,
         reviewed_by   = case when st in ('failure', 'failed') then 'UPI app: payment failed' else reviewed_by end
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then
    select * into pay from public.payments where id = p_payment_id and user_id = auth.uid();
    if not found then raise exception 'Order not found'; end if;
    return pay;
  end if;

  -- A SUCCESS that doesn't hold up (other order, no transaction ID, too late, ID already used)
  -- activates nothing; the order stays open with the answer saved for the admin.
  if st = 'success'
     and (nullif(f ->> 'txnref', '') is null or upper(f ->> 'txnref') = upper(pay.reference))
     and pay.created_at > now() - interval '2 hours' then
    txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
    if txn ~ '^[A-Z0-9]{6,40}$'
       and not exists (select 1 from public.payments where id <> pay.id and upper(txn_id) = txn) then
      return public.apply_verified_payment(pay.id, pay.amount, txn, null, 'upi_app', null);
    end if;
  end if;
  return public.try_verify_payment(pay.id);
end $$;
revoke execute on function public.report_upi_result(uuid, text) from public, anon;
grant execute on function public.report_upi_result(uuid, text) to authenticated;

-- =====================================================================
-- 24. Ads installs browse without signing in. An install whose referrer
--     says Meta (attribution_installs.source = 'meta') sees the
--     STREAMS_SPECIAL titles before login, and can watch trailers (the
--     "trailer-url" Edge Function signs trailer links for it). Full videos
--     still need sign-in + plan, exactly as before.
-- =====================================================================
create or replace function public.is_meta_install(p_install_id uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.attribution_installs
                  where install_id = p_install_id and source = 'meta')
$$;

create or replace function public.catalog_for_install(p_install_id uuid)
returns setof public.titles language sql stable security definer set search_path = public as $$
  select t.* from public.titles t
   where t.published and t.tier <> 'hidden_premium'
     and (not ('STREAMS_SPECIAL' = any (t.tags)) or public.is_meta_install(p_install_id))
   order by t.created_at desc
$$;

create or replace function public.episodes_for_install(p_install_id uuid, p_title_id uuid)
returns setof public.episodes language sql stable security definer set search_path = public as $$
  select e.* from public.episodes e
   where e.title_id = p_title_id
     and exists (select 1 from public.catalog_for_install(p_install_id) t where t.id = p_title_id)
   order by e.episode_number
$$;

revoke execute on function public.is_meta_install(uuid) from public, anon, authenticated;
revoke execute on function public.catalog_for_install(uuid) from public;
revoke execute on function public.episodes_for_install(uuid, uuid) from public;
grant execute on function public.catalog_for_install(uuid) to anon, authenticated;
grant execute on function public.episodes_for_install(uuid, uuid) to anon, authenticated;

-- =====================================================================
-- 25. VELTORA FREE MODE. While app_settings.free_mode is true every
--     signed-in user counts as subscribed: all titles play and Cloud
--     Storage uploads work (cloud_quota_gb = 15 GB each). The app hides
--     plans and payments. Turn it off to sell plans again (the UPI
--     payment system above stays in place).
-- =====================================================================
alter table public.app_settings add column if not exists free_mode boolean not null default false;
update public.app_settings set free_mode = true, cloud_quota_gb = 15 where id = 1;

create or replace function public.has_active_subscription()
returns boolean language sql stable security definer set search_path = public as $$
  select auth.uid() is not null and (
    coalesce((select free_mode from public.app_settings where id = 1), false)
    or exists (
      select 1 from public.subscriptions
      where user_id = auth.uid() and status = 'active' and expires_at > now()
    ))
$$;

-- =====================================================================
-- 26. CREATOR CHANNELS. Any signed-in user can ask for a channel (name,
--     description, photo). The admin approves or rejects the request
--     and picks who sees the channel: everyone ('all'), only organic
--     installs ('organic') or only Meta-ads installs ('ads'). Once a
--     channel is approved its owner uploads videos to it; every upload
--     waits for the admin's approval, who also picks its audience.
--     Owners change the channel photo/details and edit or delete their
--     own uploads. Admin-made channels and titles are unaffected
--     (approved, audience 'all').
--     Creator files live under creator/<user id>/ in the "images" and
--     "videos" buckets.
-- =====================================================================
alter table public.channels add column if not exists owner_id uuid references auth.users(id) on delete cascade;
alter table public.channels add column if not exists status text not null default 'approved' check (status in ('pending', 'approved', 'rejected'));
alter table public.channels add column if not exists audience text not null default 'all' check (audience in ('all', 'organic', 'ads'));
alter table public.channels add column if not exists avatar_path text;
alter table public.channels add column if not exists review_note text not null default '';
alter table public.channels add column if not exists updated_at timestamptz not null default now();
create index if not exists channels_owner_idx on public.channels (owner_id);

alter table public.titles add column if not exists uploaded_by uuid references auth.users(id) on delete cascade;
alter table public.titles add column if not exists review_status text not null default 'approved' check (review_status in ('pending', 'approved', 'rejected'));
alter table public.titles add column if not exists audience text not null default 'all' check (audience in ('all', 'organic', 'ads'));
alter table public.titles add column if not exists review_note text not null default '';
create index if not exists titles_uploaded_by_idx on public.titles (uploaded_by);

-- Does the current viewer count as a Meta-ads user? (false for guests;
-- guests go through the *_for_install functions instead.)
create or replace function public.viewer_is_meta()
returns boolean language sql stable security definer set search_path = public as $$
  select auth.uid() is not null and public.is_meta_user()
$$;

create or replace function public.audience_ok(p_audience text, p_meta boolean)
returns boolean language sql immutable set search_path = public as $$
  select p_audience = 'all' or (p_audience = 'ads' and p_meta) or (p_audience = 'organic' and not p_meta)
$$;

create or replace function public.channel_open(p_channel_id uuid, p_meta boolean)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.channels c
                  where c.id = p_channel_id and c.status = 'approved'
                    and public.audience_ok(c.audience, p_meta))
$$;

-- Titles a viewer with this Meta flag may see (besides drafts/premium rules).
create or replace function public.title_open(t public.titles, p_meta boolean)
returns boolean language sql stable security definer set search_path = public as $$
  select t.published and t.review_status = 'approved'
     and (not ('STREAMS_SPECIAL' = any (t.tags)) or p_meta)
     and public.audience_ok(t.audience, p_meta)
     and (t.channel_id is null or public.channel_open(t.channel_id, p_meta))
$$;

alter policy "anyone reads channels" on public.channels using (
  public.is_admin()
  or (owner_id is not null and owner_id = auth.uid())
  or (status = 'approved' and public.audience_ok(audience, public.viewer_is_meta()))
);

alter policy "visible titles" on public.titles using (
  public.is_admin()
  or (uploaded_by is not null and uploaded_by = auth.uid())
  or (public.title_open(titles, public.viewer_is_meta())
      and (tier <> 'hidden_premium' or public.has_active_subscription()))
);

create or replace function public.can_stream(object_name text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare t public.titles; m boolean := public.viewer_is_meta();
begin
  if public.is_admin() then return true; end if;
  if auth.uid() is null then return false; end if;
  -- a creator always plays their own files
  if split_part(object_name, '/', 1) = 'creator' and split_part(object_name, '/', 2) = auth.uid()::text then
    return true;
  end if;

  select * into t from public.titles
   where published and (trailer_path = object_name or video_path = object_name)
   limit 1;
  if not found then
    select tt.* into t from public.episodes e join public.titles tt on tt.id = e.title_id
     where tt.published and e.video_path = object_name limit 1;
    if not found then return false; end if;
  end if;

  if not public.title_open(t, m) then return false; end if;
  if t.tier = 'hidden_premium' then return public.has_active_subscription(); end if;
  if t.trailer_path = object_name then return true; end if;
  if t.tier = 'free' then return true; end if;
  return public.has_active_subscription() or t.trailer_path is null;
end $$;

create or replace function public.catalog_for_install(p_install_id uuid)
returns setof public.titles language sql stable security definer set search_path = public as $$
  select t.* from public.titles t
   where t.tier <> 'hidden_premium'
     and public.title_open(t, public.is_meta_install(p_install_id))
   order by t.created_at desc
$$;

create or replace function public.channels_for_install(p_install_id uuid)
returns setof public.channels language sql stable security definer set search_path = public as $$
  select c.* from public.channels c
   where c.status = 'approved' and public.audience_ok(c.audience, public.is_meta_install(p_install_id))
   order by c.sort_order, c.name
$$;

-- ---------- creator side ----------
create or replace function public.has_approved_channel()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.channels where owner_id = auth.uid() and status = 'approved')
$$;

create or replace function public.is_own_creator_path(p text)
returns boolean language sql stable set search_path = public as $$
  select p is null or (auth.uid() is not null and p like 'creator/' || auth.uid()::text || '/%' and p not like '%..%')
$$;

create or replace function public.request_channel(p_name text, p_description text, p_avatar_path text)
returns public.channels language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid(); n text := btrim(coalesce(p_name, '')); c public.channels;
begin
  if uid is null then raise exception 'Sign in to create a channel'; end if;
  if length(n) < 2 or length(n) > 60 then raise exception 'Channel name must be 2-60 characters'; end if;
  if length(coalesce(p_description, '')) > 1000 then raise exception 'Description is too long'; end if;
  if not public.is_own_creator_path(nullif(p_avatar_path, '')) then raise exception 'Invalid photo'; end if;
  if exists (select 1 from public.channels where owner_id = uid and status = 'pending') then
    raise exception 'You already have a channel request waiting for approval';
  end if;
  if (select count(*) from public.channels where owner_id = uid) >= 5 then
    raise exception 'You can have at most 5 channels';
  end if;
  insert into public.channels (name, description, owner_id, status, audience, avatar_path, sort_order)
  values (n, btrim(coalesce(p_description, '')), uid, 'pending', 'all', nullif(p_avatar_path, ''), 1000)
  returning * into c;
  return c;
end $$;

-- Owner edits name/description/photo. A rejected request is sent for review again.
create or replace function public.update_my_channel(p_id uuid, p_name text, p_description text, p_avatar_path text)
returns public.channels language plpgsql security definer set search_path = public as $$
declare n text := btrim(coalesce(p_name, '')); c public.channels;
begin
  select * into c from public.channels where id = p_id and owner_id = auth.uid() for update;
  if not found then raise exception 'Channel not found'; end if;
  if length(n) < 2 or length(n) > 60 then raise exception 'Channel name must be 2-60 characters'; end if;
  if length(coalesce(p_description, '')) > 1000 then raise exception 'Description is too long'; end if;
  if not public.is_own_creator_path(nullif(p_avatar_path, '')) then raise exception 'Invalid photo'; end if;
  update public.channels
     set name = n, description = btrim(coalesce(p_description, '')), avatar_path = nullif(p_avatar_path, ''),
         status = case when status = 'rejected' then 'pending' else status end,
         updated_at = now()
   where id = p_id
  returning * into c;
  return c;
end $$;

-- Create (p_id null) or edit one of the owner's videos. New videos and
-- changed video files go back to the admin for approval.
create or replace function public.creator_save_title(
  p_id uuid, p_channel_id uuid, p_name text, p_description text,
  p_cover_path text, p_video_path text, p_trailer_path text, p_duration_seconds int)
returns public.titles language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid(); n text := btrim(coalesce(p_name, '')); t public.titles; ch public.channels;
begin
  if uid is null then raise exception 'Sign in first'; end if;
  select * into ch from public.channels where id = p_channel_id and owner_id = uid;
  if not found then raise exception 'Channel not found'; end if;
  if ch.status <> 'approved' then raise exception 'Your channel is not approved yet'; end if;
  if length(n) < 1 or length(n) > 120 then raise exception 'Title must be 1-120 characters'; end if;
  if length(coalesce(p_description, '')) > 3000 then raise exception 'Description is too long'; end if;
  if nullif(p_video_path, '') is null then raise exception 'Upload a video first'; end if;
  if not (public.is_own_creator_path(nullif(p_cover_path, '')) and public.is_own_creator_path(p_video_path)
          and public.is_own_creator_path(nullif(p_trailer_path, ''))) then
    raise exception 'Invalid file';
  end if;

  if p_id is null then
    insert into public.titles (kind, name, description, channel_id, cover_path, trailer_path, video_path,
                               duration_seconds, tier, published, uploaded_by, review_status, audience)
    values ('movie', n, btrim(coalesce(p_description, '')), p_channel_id, nullif(p_cover_path, ''),
            nullif(p_trailer_path, ''), p_video_path, p_duration_seconds, 'free', false, uid, 'pending', ch.audience)
    returning * into t;
    return t;
  end if;

  select * into t from public.titles where id = p_id and uploaded_by = uid for update;
  if not found then raise exception 'Video not found'; end if;
  update public.titles
     set name = n, description = btrim(coalesce(p_description, '')), channel_id = p_channel_id,
         cover_path = nullif(p_cover_path, ''), trailer_path = nullif(p_trailer_path, ''),
         video_path = p_video_path, duration_seconds = p_duration_seconds,
         review_status = case
           when t.review_status = 'rejected'
             or t.video_path is distinct from p_video_path
             or t.trailer_path is distinct from nullif(p_trailer_path, '') then 'pending'
           else t.review_status end,
         published = case
           when t.review_status = 'rejected'
             or t.video_path is distinct from p_video_path
             or t.trailer_path is distinct from nullif(p_trailer_path, '') then false
           else t.published end
   where id = p_id
  returning * into t;
  return t;
end $$;

-- Owners delete their own videos and channels straight from the table
-- (the app first removes the files in creator/<id>/ from storage).
create policy "creators delete own titles" on public.titles for delete to authenticated
  using (uploaded_by is not null and uploaded_by = auth.uid());
create policy "creators delete own channels" on public.channels for delete to authenticated
  using (owner_id is not null and owner_id = auth.uid());
-- ---------- admin side ----------
create or replace function public.admin_review_channel(p_id uuid, p_status text, p_audience text, p_note text)
returns public.channels language plpgsql security definer set search_path = public as $$
declare c public.channels;
begin
  if not public.is_admin() then raise exception 'Admins only'; end if;
  if p_status not in ('pending', 'approved', 'rejected') then raise exception 'Bad status'; end if;
  if p_audience not in ('all', 'organic', 'ads') then raise exception 'Bad audience'; end if;
  update public.channels
     set status = p_status, audience = p_audience, review_note = coalesce(p_note, ''), updated_at = now()
   where id = p_id
  returning * into c;
  if not found then raise exception 'Channel not found'; end if;
  return c;
end $$;

create or replace function public.admin_review_title(p_id uuid, p_status text, p_audience text, p_note text)
returns public.titles language plpgsql security definer set search_path = public as $$
declare t public.titles;
begin
  if not public.is_admin() then raise exception 'Admins only'; end if;
  if p_status not in ('pending', 'approved', 'rejected') then raise exception 'Bad status'; end if;
  if p_audience not in ('all', 'organic', 'ads') then raise exception 'Bad audience'; end if;
  update public.titles
     set review_status = p_status, audience = p_audience, review_note = coalesce(p_note, ''),
         published = (p_status = 'approved')
   where id = p_id
  returning * into t;
  if not found then raise exception 'Video not found'; end if;
  return t;
end $$;

-- Review queue for the admin panel, with the creator's email.
create or replace function public.admin_creator_channels(p_status text default null)
returns table (id uuid, name text, description text, avatar_path text, status text, audience text,
               review_note text, owner_id uuid, owner_email text, video_count bigint, created_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Admins only'; end if;
  return query
    select c.id, c.name, c.description, c.avatar_path, c.status, c.audience, c.review_note, c.owner_id,
           u.email::text, (select count(*) from public.titles t where t.channel_id = c.id), c.created_at
      from public.channels c join auth.users u on u.id = c.owner_id
     where p_status is null or c.status = p_status
     order by (c.status = 'pending') desc, c.created_at desc;
end $$;

create or replace function public.admin_creator_titles(p_status text default null)
returns table (id uuid, name text, description text, cover_path text, video_path text, trailer_path text,
               review_status text, audience text, review_note text, channel_id uuid, channel_name text,
               channel_audience text, uploader_email text, created_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Admins only'; end if;
  return query
    select t.id, t.name, t.description, t.cover_path, t.video_path, t.trailer_path, t.review_status,
           t.audience, t.review_note, t.channel_id, c.name, c.audience, u.email::text, t.created_at
      from public.titles t
      join auth.users u on u.id = t.uploaded_by
      left join public.channels c on c.id = t.channel_id
     where t.uploaded_by is not null and (p_status is null or t.review_status = p_status)
     order by (t.review_status = 'pending') desc, t.created_at desc;
end $$;

-- ---------- storage: creators write only inside creator/<their id>/ ----------
create policy "creators write own images" on storage.objects for insert to authenticated
  with check (bucket_id = 'images' and (storage.foldername(name))[1] = 'creator'
              and (storage.foldername(name))[2] = auth.uid()::text);
create policy "creators update own images" on storage.objects for update to authenticated
  using (bucket_id = 'images' and (storage.foldername(name))[1] = 'creator'
         and (storage.foldername(name))[2] = auth.uid()::text);
create policy "creators delete own images" on storage.objects for delete to authenticated
  using (bucket_id = 'images' and (storage.foldername(name))[1] = 'creator'
         and (storage.foldername(name))[2] = auth.uid()::text);
create policy "creators upload videos" on storage.objects for insert to authenticated
  with check (bucket_id = 'videos' and (storage.foldername(name))[1] = 'creator'
              and (storage.foldername(name))[2] = auth.uid()::text and public.has_approved_channel());
create policy "creators delete own videos" on storage.objects for delete to authenticated
  using (bucket_id = 'videos' and (storage.foldername(name))[1] = 'creator'
         and (storage.foldername(name))[2] = auth.uid()::text);

-- ---------- function access ----------
revoke execute on function public.title_open(public.titles, boolean) from public;
grant execute on function public.title_open(public.titles, boolean) to anon, authenticated;
revoke execute on function public.channel_open(uuid, boolean) from public;
grant execute on function public.channel_open(uuid, boolean) to anon, authenticated;
grant execute on function public.viewer_is_meta() to anon, authenticated;
grant execute on function public.audience_ok(text, boolean) to anon, authenticated;
grant execute on function public.channels_for_install(uuid) to anon, authenticated;
revoke execute on function public.request_channel(text, text, text) from public, anon;
revoke execute on function public.update_my_channel(uuid, text, text, text) from public, anon;
revoke execute on function public.creator_save_title(uuid, uuid, text, text, text, text, text, int) from public, anon;
revoke execute on function public.admin_review_channel(uuid, text, text, text) from public, anon;
revoke execute on function public.admin_review_title(uuid, text, text, text) from public, anon;
revoke execute on function public.admin_creator_channels(text) from public, anon;
revoke execute on function public.admin_creator_titles(text) from public, anon;
grant execute on function public.request_channel(text, text, text) to authenticated;
grant execute on function public.update_my_channel(uuid, text, text, text) to authenticated;
grant execute on function public.creator_save_title(uuid, uuid, text, text, text, text, text, int) to authenticated;
grant execute on function public.admin_review_channel(uuid, text, text, text) to authenticated;
grant execute on function public.admin_review_title(uuid, text, text, text) to authenticated;
grant execute on function public.admin_creator_channels(text) to authenticated;
grant execute on function public.admin_creator_titles(text) to authenticated;

-- =====================================================================
-- 27. GOOGLE PLAY: REPORTING USER CONTENT. Signed-in users report a
--     creator channel or video (Play's user-generated-content policy);
--     the admin sees open reports and resolves them (and unpublishes the
--     channel/video from Admin → Creators when needed). Account deletion
--     is done by the "delete-account" Edge Function.
-- =====================================================================
create table if not exists public.content_reports (
  id          uuid primary key default gen_random_uuid(),
  reporter_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  target_type text not null check (target_type in ('channel', 'title')),
  target_id   uuid not null,
  reason      text not null check (reason in ('sexual', 'violent', 'hateful', 'harassment', 'spam', 'copyright', 'child_safety', 'other')),
  details     text not null default '' check (length(details) <= 1000),
  status      text not null default 'open' check (status in ('open', 'resolved')),
  admin_note  text not null default '',
  created_at  timestamptz not null default now()
);
alter table public.content_reports enable row level security;
create index if not exists content_reports_status_idx on public.content_reports (status, created_at desc);
create policy "users file reports" on public.content_reports for insert to authenticated
  with check (reporter_id = auth.uid() and status = 'open' and admin_note = '');
create policy "admins read reports" on public.content_reports for select using (public.is_admin());
create policy "admins resolve reports" on public.content_reports for update using (public.is_admin()) with check (public.is_admin());

-- Open/closed reports with the reporter's email and what was reported.
create or replace function public.admin_reports(p_status text default 'open')
returns table (id uuid, target_type text, target_id uuid, target_name text, channel_name text,
               owner_email text, reason text, details text, status text, admin_note text,
               reporter_email text, report_count bigint, created_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Admins only'; end if;
  return query
    select r.id, r.target_type, r.target_id,
           coalesce(t.name, c.name, '(deleted)'),
           coalesce(tc.name, c.name),
           coalesce(tu.email, cu.email)::text,
           r.reason, r.details, r.status, r.admin_note, ru.email::text,
           (select count(*) from public.content_reports x where x.target_id = r.target_id and x.status = 'open'),
           r.created_at
      from public.content_reports r
      left join auth.users ru on ru.id = r.reporter_id
      left join public.titles t on r.target_type = 'title' and t.id = r.target_id
      left join public.channels tc on tc.id = t.channel_id
      left join auth.users tu on tu.id = t.uploaded_by
      left join public.channels c on r.target_type = 'channel' and c.id = r.target_id
      left join auth.users cu on cu.id = c.owner_id
     where p_status is null or r.status = p_status
     order by r.created_at desc;
end $$;
revoke execute on function public.admin_reports(text) from public, anon;
grant execute on function public.admin_reports(text) to authenticated;

-- =====================================================================
-- 28. META (FACEBOOK) ADS TRACKING. The owner enters the Meta IDs in the
--     admin panel. Public values (App ID + Client Token for the Android
--     app's SDK, Pixel / dataset ID for the website) live in app_settings;
--     the Conversions API access token lives in meta_secrets, which no
--     client can read (only the "meta-capi" Edge Function, service role).
--     Registration and Purchase events are claimed once per account /
--     payment here, so the app and the website never report them twice.
-- =====================================================================
alter table public.app_settings add column if not exists meta_enabled boolean not null default false;
alter table public.app_settings add column if not exists meta_app_id text;
alter table public.app_settings add column if not exists meta_client_token text;
alter table public.app_settings add column if not exists meta_pixel_id text;

create table if not exists public.meta_secrets (
  id              int primary key default 1 check (id = 1),
  capi_token      text,
  test_event_code text,
  updated_at      timestamptz not null default now()
);
alter table public.meta_secrets enable row level security;   -- no policies: service role only

create table if not exists public.meta_logged (
  user_id    uuid not null references auth.users(id) on delete cascade,
  key        text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, key)
);
alter table public.meta_logged enable row level security;    -- no policies: written by the functions below

-- Owner saves the Conversions API token (empty string keeps the saved one) and test code.
create or replace function public.admin_set_meta_capi(p_token text, p_test_event_code text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can change Meta settings'; end if;
  insert into public.meta_secrets (id, capi_token, test_event_code, updated_at)
  values (1, nullif(btrim(coalesce(p_token, '')), ''), nullif(btrim(coalesce(p_test_event_code, '')), ''), now())
  on conflict (id) do update
     set capi_token = coalesce(nullif(btrim(coalesce(p_token, '')), ''), meta_secrets.capi_token),
         test_event_code = nullif(btrim(coalesce(p_test_event_code, '')), ''),
         updated_at = now();
  return public.admin_meta_status();
end $$;

create or replace function public.admin_clear_meta_capi()
returns json language plpgsql security definer set search_path = public as $$
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can change Meta settings'; end if;
  update public.meta_secrets set capi_token = null, updated_at = now() where id = 1;
  return public.admin_meta_status();
end $$;

-- Never returns the token itself.
create or replace function public.admin_meta_status()
returns json language plpgsql stable security definer set search_path = public as $$
declare s public.meta_secrets;
begin
  if not public.is_admin() then raise exception 'Admins only'; end if;
  select * into s from public.meta_secrets where id = 1;
  return json_build_object(
    'has_token', coalesce(s.capi_token, '') <> '',
    'token_hint', case when coalesce(s.capi_token, '') <> '' then '…' || right(s.capi_token, 4) end,
    'test_event_code', s.test_event_code,
    'updated_at', s.updated_at);
end $$;

-- true once per account, and only for accounts created in the last 2 days.
create or replace function public.claim_meta_registration()
returns boolean language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid(); n int;
begin
  if uid is null then return false; end if;
  if not exists (select 1 from auth.users where id = uid and created_at > now() - interval '2 days') then
    return false;
  end if;
  insert into public.meta_logged (user_id, key) values (uid, 'registration') on conflict do nothing;
  get diagnostics n = row_count;
  return n = 1;
end $$;

-- The current user's approved payments (last 7 days) not reported to Meta yet; each is returned once.
create or replace function public.claim_meta_purchases()
returns table (payment_id uuid, amount numeric, currency text, plan_name text)
language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid();
begin
  if uid is null then return; end if;
  return query
    with fresh as (
      insert into public.meta_logged (user_id, key)
      select uid, 'purchase:' || p.id from public.payments p
       where p.user_id = uid and p.status = 'approved'
         and coalesce(p.verified_at, p.reviewed_at, p.updated_at, p.created_at) > now() - interval '7 days'
      on conflict do nothing
      returning key)
    select p.id, p.amount, coalesce(p.currency, 'INR'), p.plan_name
      from fresh f join public.payments p on 'purchase:' || p.id = f.key;
end $$;

revoke execute on function public.admin_set_meta_capi(text, text) from public, anon;
revoke execute on function public.admin_clear_meta_capi() from public, anon;
revoke execute on function public.admin_meta_status() from public, anon;
revoke execute on function public.claim_meta_registration() from public, anon;
revoke execute on function public.claim_meta_purchases() from public, anon;
grant execute on function public.admin_set_meta_capi(text, text) to authenticated;
grant execute on function public.admin_clear_meta_capi() to authenticated;
grant execute on function public.admin_meta_status() to authenticated;
grant execute on function public.claim_meta_registration() to authenticated;
grant execute on function public.claim_meta_purchases() to authenticated;

-- =====================================================================
-- 29. APPROVED CHANNELS PUBLISH DIRECTLY + JOINING CHANNELS.
--     Once the admin approves a channel, its owner's uploads go live at
--     once for that channel's audience (the admin can still unpublish a
--     video in Admin → Creators). Changing a channel's audience moves its
--     creator videos to the same audience. Signed-in users join (follow)
--     the channels they are allowed to see.
-- =====================================================================
create or replace function public.creator_save_title(
  p_id uuid, p_channel_id uuid, p_name text, p_description text,
  p_cover_path text, p_video_path text, p_trailer_path text, p_duration_seconds int)
returns public.titles language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid(); n text := btrim(coalesce(p_name, '')); t public.titles; ch public.channels;
begin
  if uid is null then raise exception 'Sign in first'; end if;
  select * into ch from public.channels where id = p_channel_id and owner_id = uid;
  if not found then raise exception 'Channel not found'; end if;
  if ch.status <> 'approved' then raise exception 'Your channel is not approved yet'; end if;
  if length(n) < 1 or length(n) > 120 then raise exception 'Title must be 1-120 characters'; end if;
  if length(coalesce(p_description, '')) > 3000 then raise exception 'Description is too long'; end if;
  if nullif(p_video_path, '') is null then raise exception 'Upload a video first'; end if;
  if not (public.is_own_creator_path(nullif(p_cover_path, '')) and public.is_own_creator_path(p_video_path)
          and public.is_own_creator_path(nullif(p_trailer_path, ''))) then
    raise exception 'Invalid file';
  end if;

  if p_id is null then
    -- approved channel: live straight away, for the channel's audience
    insert into public.titles (kind, name, description, channel_id, cover_path, trailer_path, video_path,
                               duration_seconds, tier, published, uploaded_by, review_status, audience)
    values ('movie', n, btrim(coalesce(p_description, '')), p_channel_id, nullif(p_cover_path, ''),
            nullif(p_trailer_path, ''), p_video_path, p_duration_seconds, 'free', true, uid, 'approved', ch.audience)
    returning * into t;
    return t;
  end if;

  select * into t from public.titles where id = p_id and uploaded_by = uid for update;
  if not found then raise exception 'Video not found'; end if;
  -- edits keep the admin's decision: an unpublished (rejected) video stays unpublished
  update public.titles
     set name = n, description = btrim(coalesce(p_description, '')), channel_id = p_channel_id,
         cover_path = nullif(p_cover_path, ''), trailer_path = nullif(p_trailer_path, ''),
         video_path = p_video_path, duration_seconds = p_duration_seconds,
         review_status = case when t.review_status = 'pending' then 'approved' else t.review_status end,
         published = (case when t.review_status = 'pending' then 'approved' else t.review_status end) = 'approved',
         audience = case when t.channel_id is distinct from p_channel_id then ch.audience else t.audience end
   where id = p_id
  returning * into t;
  return t;
end $$;

-- Approving / re-targeting a channel also applies to its creator videos.
create or replace function public.admin_review_channel(p_id uuid, p_status text, p_audience text, p_note text)
returns public.channels language plpgsql security definer set search_path = public as $$
declare c public.channels;
begin
  if not public.is_admin() then raise exception 'Admins only'; end if;
  if p_status not in ('pending', 'approved', 'rejected') then raise exception 'Bad status'; end if;
  if p_audience not in ('all', 'organic', 'ads') then raise exception 'Bad audience'; end if;
  update public.channels
     set status = p_status, audience = p_audience, review_note = coalesce(p_note, ''), updated_at = now()
   where id = p_id
  returning * into c;
  if not found then raise exception 'Channel not found'; end if;
  update public.titles set audience = p_audience where channel_id = p_id and uploaded_by is not null;
  if p_status = 'approved' then
    update public.titles set review_status = 'approved', published = true
     where channel_id = p_id and uploaded_by is not null and review_status = 'pending';
  end if;
  return c;
end $$;

-- Videos waiting under the old rules in channels that are already approved go live now.
update public.titles t set review_status = 'approved', published = true, audience = c.audience
  from public.channels c
 where c.id = t.channel_id and c.status = 'approved' and t.uploaded_by is not null and t.review_status = 'pending';

-- ---------- joining channels ----------
create table if not exists public.channel_members (
  channel_id uuid not null references public.channels(id) on delete cascade,
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (channel_id, user_id)
);
alter table public.channel_members enable row level security;
create index if not exists channel_members_user_idx on public.channel_members (user_id);
create policy "members read own joins" on public.channel_members for select to authenticated
  using (user_id = auth.uid());
create policy "members join visible channels" on public.channel_members for insert to authenticated
  with check (user_id = auth.uid() and public.channel_open(channel_id, public.viewer_is_meta()));
create policy "members leave" on public.channel_members for delete to authenticated
  using (user_id = auth.uid());

-- Member counts for channels this viewer can see.
create or replace function public.channel_member_counts()
returns table (channel_id uuid, members bigint)
language sql stable security definer set search_path = public as $$
  select m.channel_id, count(*) from public.channel_members m
   where public.channel_open(m.channel_id, public.viewer_is_meta())
   group by m.channel_id
$$;
grant execute on function public.channel_member_counts() to anon, authenticated;

-- =====================================================================
-- 30. ADS USERS ACTIVATE AT ONCE, ORGANIC USERS WAIT FOR THE ADMIN.
--     A UPI SUCCESS from an account that came from Meta ads activates the
--     plan straight away (as before). The same answer from any other
--     account puts the order in Payments → Pending with its transaction
--     ID; the plan starts only when the admin taps Approve.
-- =====================================================================
create or replace function public.report_upi_result(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  f   jsonb := public.parse_upi_response(p_response);
  st  text := lower(coalesce(f ->> 'status', ''));
  txn text;
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments
     set upi_response  = left(p_response, 2000),
         client_status = nullif(st, ''),
         updated_at    = now(),
         status        = case when st in ('failure', 'failed') then 'failed' else status end,
         reviewed_at   = case when st in ('failure', 'failed') then now() else reviewed_at end,
         reviewed_by   = case when st in ('failure', 'failed') then 'UPI app: payment failed' else reviewed_by end
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then
    select * into pay from public.payments where id = p_payment_id and user_id = auth.uid();
    if not found then raise exception 'Order not found'; end if;
    return pay;
  end if;

  if st = 'success'
     and (nullif(f ->> 'txnref', '') is null or upper(f ->> 'txnref') = upper(pay.reference))
     and pay.created_at > now() - interval '2 hours' then
    txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
    if txn ~ '^[A-Z0-9]{6,40}$'
       and not exists (select 1 from public.payments where id <> pay.id and upper(txn_id) = txn) then
      if public.is_meta_user() then
        return public.apply_verified_payment(pay.id, pay.amount, txn, null, 'upi_app', null);
      end if;
      -- organic account: hold for the admin
      update public.payments
         set status = 'pending', txn_id = txn, reviewed_by = 'Waiting for admin approval', updated_at = now()
       where id = pay.id
      returning * into pay;
      return pay;
    end if;
  end if;
  return public.try_verify_payment(pay.id);
end $$;

-- Approve keeps the UPI transaction ID when there is one.
create or replace function public.approve_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  select * into pay from public.payments where id = p_payment_id;
  if not found or pay.status <> 'pending' then raise exception 'This payment was already processed'; end if;
  return public.apply_verified_payment(pay.id, pay.amount, coalesce(pay.txn_id, pay.reference), null, 'admin', null);
end $$;

-- Every payment carries the buyer's source (meta / organic / ...) from the moment it is
-- created, so the admin can tell ads orders from organic ones while they wait for approval.
create or replace function public.payments_attribution()
returns trigger language plpgsql security definer set search_path = public as $$
declare ua public.user_attribution; had_active boolean;
begin
  if new.attribution_source is null or (new.status = 'approved' and old.status is distinct from 'approved') then
    select * into ua from public.user_attribution where user_id = new.user_id;
    new.attribution_source   := coalesce(ua.first_touch_source, 'unknown');
    new.attribution_campaign := ua.first_touch_campaign;
    new.attribution_content  := ua.first_touch_content;
  end if;
  if tg_op = 'UPDATE' and new.status = 'approved' and old.status is distinct from 'approved' then
    select exists (select 1 from public.subscriptions
                    where user_id = new.user_id and status = 'active' and expires_at > now()) into had_active;
    insert into public.analytics_events (event, user_id, source, campaign, plan_id)
    values ('payment_success', new.user_id, new.attribution_source, new.attribution_campaign, new.plan_id);
    if not had_active then
      insert into public.analytics_events (event, user_id, source, campaign, plan_id)
      values ('subscription_started', new.user_id, new.attribution_source, new.attribution_campaign, new.plan_id);
    end if;
  end if;
  return new;
end $$;
create or replace trigger payments_attribution before insert or update on public.payments
  for each row execute function public.payments_attribution();
update public.payments p set attribution_source = coalesce(ua.first_touch_source, 'unknown'),
       attribution_campaign = ua.first_touch_campaign, attribution_content = ua.first_touch_content
  from public.payments p2 left join public.user_attribution ua on ua.user_id = p2.user_id
 where p.id = p2.id and p.attribution_source is null;

-- =====================================================================
-- 31. BANK SMS AUTO-VERIFY (no payment gateway)
--     * Every new order gets a unique amount: plan price + 1..99 paise, so one
--       bank credit points at exactly one open order.
--     * The owner's phone forwards the bank's "credited" SMS to the bank-sms Edge
--       Function (secret key in the URL), which calls ingest_bank_sms().
--     * Only SMS from the bank's sender name (set in Admin) are used; SMS from
--       ordinary phone numbers are ignored, so a forged SMS can't unlock a plan.
--     * Ads (Meta) buyers are activated at once; organic buyers' orders move to
--       "Needs approval" with the bank proof attached, for the admin to approve.
-- =====================================================================
create table if not exists public.bank_sms_settings (
  id            int primary key default 1 check (id = 1),
  enabled       boolean not null default false,
  sender_filter text,
  key_hash      text,
  updated_at    timestamptz not null default now()
);
alter table public.bank_sms_settings enable row level security;   -- no policies: functions only
insert into public.bank_sms_settings (id) values (1) on conflict (id) do nothing;

create table if not exists public.bank_sms_log (
  id          bigint generated always as identity primary key,
  received_at timestamptz not null default now(),
  sender      text,
  result      text,
  amount      numeric(10,2),
  ref         text,
  payment_id  uuid
);
alter table public.bank_sms_log enable row level security;
do $$ begin
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'bank_sms_log' and policyname = 'admins read sms log') then
    create policy "admins read sms log" on public.bank_sms_log for select using (public.is_admin());
  end if;
end $$;

-- Unique amount per open order (existing open order for the same plan is reused as before).
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments; amt numeric(10,2); i int;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if not exists (select 1 from public.subscription_plans where id = p_plan_id and active) then
    raise exception 'This plan is not available';
  end if;
  select * into r from public.payments
   where user_id = auth.uid() and status = 'initiated' and plan_id = p_plan_id
     and created_at > now() - interval '24 hours'
   order by created_at desc limit 1;
  if found then return r; end if;
  update public.payments set status = 'cancelled', updated_at = now(), reviewed_at = now(), reviewed_by = 'replaced by a new order'
   where user_id = auth.uid() and status = 'initiated';
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id,
          'UPI' || to_char(now() at time zone 'Asia/Kolkata', 'YYYYMMDD')
                || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 8)),
          'initiated')
  returning * into r;   -- amount = plan price (payments_before_insert)
  for i in 1..60 loop
    amt := r.amount + (1 + floor(random() * 99))::int / 100.0;
    exit when not exists (
      select 1 from public.payments
       where id <> r.id and amount = amt and created_at > now() - interval '24 hours'
         and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null)));
  end loop;
  update public.payments set amount = amt, updated_at = now() where id = r.id returning * into r;
  return r;
end $$;

create or replace function public.user_is_meta(p_user uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.user_attribution where user_id = p_user and first_touch_source = 'meta')
$$;
revoke execute on function public.user_is_meta(uuid) from public, anon, authenticated;

-- Attach one bank credit to one order, following the ads / organic rule.
create or replace function public.settle_bank_credit(p_credit uuid, p_payment uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare c public.bank_credits; pay public.payments;
begin
  select * into c from public.bank_credits where id = p_credit for update;
  select * into pay from public.payments where id = p_payment for update;
  if c.id is null or pay.id is null or c.payment_id is not null or c.amount <> pay.amount then return pay; end if;
  if public.user_is_meta(pay.user_id) then
    pay := public.apply_verified_payment(pay.id, c.amount, c.ref, null, 'bank_credit', c.id);
  else
    update public.payments
       set status = 'pending', bank_credit_id = c.id, updated_at = now(),
           txn_id = coalesce(txn_id, case when c.ref is not null and not exists (
                      select 1 from public.payments p2 where p2.id <> pay.id and upper(p2.txn_id) = c.ref) then c.ref end),
           reviewed_by = 'Paid (bank SMS confirmed) - waiting for admin approval'
     where id = pay.id
    returning * into pay;
  end if;
  update public.bank_credits set payment_id = pay.id where id = c.id;
  return pay;
end $$;
revoke execute on function public.settle_bank_credit(uuid, uuid) from public, anon, authenticated;

-- Customer polling ("Check payment status") also picks up a credit that arrived earlier.
create or replace function public.try_verify_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments; c public.bank_credits;
begin
  select * into pay from public.payments where id = p_payment_id;
  if not found then return pay; end if;
  if not (pay.status in ('initiated', 'failed', 'cancelled') or (pay.status = 'pending' and pay.bank_credit_id is null)) then
    return pay;
  end if;
  select * into c from public.bank_credits
   where payment_id is null and amount = pay.amount
     and received_at >= pay.created_at - interval '2 minutes'
     and received_at <= pay.created_at + interval '24 hours'
   order by received_at limit 1 for update skip locked;
  if not found then return pay; end if;
  return public.settle_bank_credit(c.id, pay.id);
end $$;
revoke execute on function public.try_verify_payment(uuid) from public, anon, authenticated;

-- Reads one forwarded SMS. Called only by the bank-sms Edge Function (service role).
create or replace function public.ingest_bank_sms(p_key text, p_sender text, p_text text)
returns json language plpgsql security definer set search_path = public as $$
declare
  s      public.bank_sms_settings;
  t      text := left(coalesce(p_text, ''), 1000);
  lt     text;
  snd    text := left(btrim(coalesce(p_sender, '')), 80);
  m      text[];
  amt    numeric(10,2);
  v_ref  text;
  c      public.bank_credits;
  pay    public.payments;
  target uuid;
  open_n int;
  res    text;
begin
  select * into s from public.bank_sms_settings where id = 1;
  if s.key_hash is null or coalesce(p_key, '') = ''
     or encode(sha256(convert_to(p_key, 'UTF8')), 'hex') <> s.key_hash then
    return json_build_object('ok', false, 'error', 'bad key');
  end if;
  lt := lower(t);
  if not s.enabled then res := 'ignored: auto-verify is off';
  elsif coalesce(btrim(s.sender_filter), '') = '' then res := 'ignored: bank sender name not set';
  elsif position(upper(btrim(s.sender_filter)) in upper(snd)) = 0 then res := 'ignored: sender does not match';
  elsif snd ~ '^\+?[0-9 ()-]+$' then res := 'ignored: sent from a phone number';
  elsif lt ~ '\mdebit' then res := 'ignored: debit message';
  elsif lt !~ '(credited|\mcr\M|received|deposited)' then res := 'ignored: not a credit message';
  else
    m := regexp_match(t, '(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)', 'i');
    amt := case when m is null then null else nullif(replace(m[1], ',', ''), '')::numeric end;
    if amt is null or amt <= 0 then
      res := 'ignored: amount not found';
    else
      m := regexp_match(t, '(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn)[^0-9]{0,15}([0-9]{12})', 'i');
      if m is null then m := regexp_match(t, '\m([0-9]{12})\M'); end if;
      v_ref := m[1];
      select * into c from public.bank_credits
       where (v_ref is not null and ref = v_ref)
          or (v_ref is null and amount = amt and received_at > now() - interval '3 minutes')
       limit 1;
      if found then
        res := 'duplicate';
      else
        select count(*) into open_n from public.payments
         where amount = amt and created_at > now() - interval '24 hours'
           and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null));
        insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
        values (amt, v_ref, left('sms:' || snd, 80), left(t, 500), 'sms-forwarder', open_n > 1)
        returning * into c;
        select id into target from public.payments
         where amount = amt and created_at > now() - interval '24 hours'
           and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null))
         order by (status = 'initiated') desc, created_at desc
         limit 1 for update skip locked;
        if target is not null then
          pay := public.settle_bank_credit(c.id, target);
          res := case when pay.status = 'approved' then 'activated' else 'matched: waiting for admin approval' end;
        else
          -- Already activated from the UPI app? Attach the bank proof to it.
          update public.payments set bank_credit_id = c.id, updated_at = now()
           where id = (select id from public.payments
                        where status = 'approved' and bank_credit_id is null and amount = amt
                          and created_at > now() - interval '24 hours'
                        order by created_at desc limit 1)
          returning * into pay;
          if pay.id is not null then
            update public.bank_credits set payment_id = pay.id where id = c.id;
            res := 'proof added to an active order';
          else
            res := 'no matching order';
          end if;
        end if;
      end if;
    end if;
  end if;
  insert into public.bank_sms_log (sender, result, amount, ref, payment_id) values (snd, res, amt, v_ref, pay.id);
  return json_build_object('ok', true, 'result', res, 'amount', amt, 'ref', v_ref, 'payment_id', pay.id);
end $$;
revoke execute on function public.ingest_bank_sms(text, text, text) from public, anon, authenticated;
grant execute on function public.ingest_bank_sms(text, text, text) to service_role;

-- Admin → Settings → Bank SMS auto-verify
create or replace function public.admin_bank_sms_status()
returns json language plpgsql stable security definer set search_path = public as $$
declare s public.bank_sms_settings;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  select * into s from public.bank_sms_settings where id = 1;
  return json_build_object('enabled', coalesce(s.enabled, false), 'sender_filter', s.sender_filter,
                           'has_key', s.key_hash is not null, 'updated_at', s.updated_at);
end $$;
revoke execute on function public.admin_bank_sms_status() from public, anon;
grant execute on function public.admin_bank_sms_status() to authenticated;

create or replace function public.admin_set_bank_sms(p_enabled boolean, p_sender text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can change this'; end if;
  update public.bank_sms_settings
     set enabled = coalesce(p_enabled, false), sender_filter = nullif(btrim(coalesce(p_sender, '')), ''), updated_at = now()
   where id = 1;
  return public.admin_bank_sms_status();
end $$;
revoke execute on function public.admin_set_bank_sms(boolean, text) from public, anon;
grant execute on function public.admin_set_bank_sms(boolean, text) to authenticated;

-- Returns a new secret key once (only its hash is stored). The old key stops working.
create or replace function public.admin_new_bank_sms_key()
returns text language plpgsql security definer set search_path = public as $$
declare k text := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can change this'; end if;
  update public.bank_sms_settings set key_hash = encode(sha256(convert_to(k, 'UTF8')), 'hex'), updated_at = now() where id = 1;
  return k;
end $$;
revoke execute on function public.admin_new_bank_sms_key() from public, anon;
grant execute on function public.admin_new_bank_sms_key() to authenticated;

-- =====================================================================
-- 32. EXACT PLAN PRICE (owner's decision: no extra paise).
--     A bank credit is matched to an order by:
--       1. its UPI reference number (the UTR the customer typed, or the UPI app's
--          transaction ID), else
--       2. its amount, but only when exactly one open order has that amount in the
--          last 30 minutes. With several, the credit waits until a customer enters
--          the UTR from their UPI app, which then matches it automatically.
-- =====================================================================
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if not exists (select 1 from public.subscription_plans where id = p_plan_id and active) then
    raise exception 'This plan is not available';
  end if;
  select * into r from public.payments
   where user_id = auth.uid() and status = 'initiated' and plan_id = p_plan_id
     and created_at > now() - interval '24 hours'
     and amount = (select price from public.subscription_plans where id = p_plan_id)
   order by created_at desc limit 1;
  if found then return r; end if;
  update public.payments set status = 'cancelled', updated_at = now(), reviewed_at = now(), reviewed_by = 'replaced by a new order'
   where user_id = auth.uid() and status = 'initiated';
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id,
          'UPI' || to_char(now() at time zone 'Asia/Kolkata', 'YYYYMMDD')
                || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 8)),
          'initiated')
  returning * into r;   -- amount = exact plan price (payments_before_insert)
  return r;
end $$;

-- Open order that a credit of this amount / reference belongs to (null if unclear).
create or replace function public.find_order_for_credit(p_amount numeric, p_ref text, p_at timestamptz)
returns uuid language plpgsql stable security definer set search_path = public as $$
declare target uuid; n int;
begin
  if p_ref is not null then
    select id into target from public.payments
     where amount = p_amount and (upper(coalesce(txn_id, '')) = p_ref or upper(reference) = p_ref)
       and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null))
       and created_at > p_at - interval '48 hours'
     order by created_at desc limit 1;
    if target is not null then return target; end if;
  end if;
  select count(*), min(id::text)::uuid into n, target from public.payments
   where amount = p_amount
     and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null))
     and created_at between p_at - interval '30 minutes' and p_at + interval '2 minutes';
  return case when n = 1 then target end;
end $$;
revoke execute on function public.find_order_for_credit(numeric, text, timestamptz) from public, anon, authenticated;

create or replace function public.try_verify_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments; c public.bank_credits;
begin
  select * into pay from public.payments where id = p_payment_id;
  if not found then return pay; end if;
  if not (pay.status in ('initiated', 'failed', 'cancelled') or (pay.status = 'pending' and pay.bank_credit_id is null)) then
    return pay;
  end if;
  for c in select * from public.bank_credits
            where payment_id is null and amount = pay.amount
              and received_at >= pay.created_at - interval '2 minutes'
              and received_at <= pay.created_at + interval '48 hours'
            order by received_at loop
    if public.find_order_for_credit(c.amount, c.ref, c.received_at) = pay.id then
      return public.settle_bank_credit(c.id, pay.id);
    end if;
  end loop;
  return pay;
end $$;
revoke execute on function public.try_verify_payment(uuid) from public, anon, authenticated;

-- "Paid already? Enter UTR": if the bank SMS with that UTR already arrived, it matches at once.
create or replace function public.submit_payment(p_plan_id uuid, p_reference text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  r   public.payments;
  v_ref text := upper(regexp_replace(coalesce(p_reference, ''), '\s', '', 'g'));
  c   public.bank_credits;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if exists (select 1 from public.payments where user_id = auth.uid() and status = 'pending') then
    raise exception 'You already have a payment waiting for verification';
  end if;
  if exists (select 1 from public.payments where reference = v_ref or upper(txn_id) = v_ref) then
    raise exception 'This reference number has already been submitted';
  end if;
  insert into public.payments (plan_id, reference) values (p_plan_id, p_reference) returning * into r;
  -- the customer's open UPI order for this plan is now covered by this one
  update public.payments set status = 'cancelled', updated_at = now(), reviewed_at = now(), reviewed_by = 'replaced by UTR entry'
   where user_id = auth.uid() and status = 'initiated';
  select * into c from public.bank_credits where payment_id is null and bank_credits.ref = r.reference and amount = r.amount limit 1;
  if found then r := public.settle_bank_credit(c.id, r.id); end if;
  return r;
end $$;
revoke execute on function public.submit_payment(uuid, text) from public, anon;
grant execute on function public.submit_payment(uuid, text) to authenticated;

create or replace function public.ingest_bank_sms(p_key text, p_sender text, p_text text)
returns json language plpgsql security definer set search_path = public as $$
declare
  s      public.bank_sms_settings;
  t      text := left(coalesce(p_text, ''), 1000);
  lt     text;
  snd    text := left(btrim(coalesce(p_sender, '')), 80);
  m      text[];
  amt    numeric(10,2);
  v_ref  text;
  c      public.bank_credits;
  pay    public.payments;
  target uuid;
  open_n int;
  res    text;
begin
  select * into s from public.bank_sms_settings where id = 1;
  if s.key_hash is null or coalesce(p_key, '') = ''
     or encode(sha256(convert_to(p_key, 'UTF8')), 'hex') <> s.key_hash then
    return json_build_object('ok', false, 'error', 'bad key');
  end if;
  lt := lower(t);
  if not s.enabled then res := 'ignored: auto-verify is off';
  elsif coalesce(btrim(s.sender_filter), '') = '' then res := 'ignored: bank sender name not set';
  elsif position(upper(btrim(s.sender_filter)) in upper(snd)) = 0 then res := 'ignored: sender does not match';
  elsif snd ~ '^\+?[0-9 ()-]+$' then res := 'ignored: sent from a phone number';
  elsif lt ~ '\mdebit' then res := 'ignored: debit message';
  elsif lt !~ '(credited|\mcr\M|received|deposited)' then res := 'ignored: not a credit message';
  else
    m := regexp_match(t, '(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)', 'i');
    amt := case when m is null then null else nullif(replace(m[1], ',', ''), '')::numeric end;
    if amt is null or amt <= 0 then
      res := 'ignored: amount not found';
    else
      m := regexp_match(t, '(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn)[^0-9]{0,15}([0-9]{12})', 'i');
      if m is null then m := regexp_match(t, '\m([0-9]{12})\M'); end if;
      v_ref := m[1];
      select * into c from public.bank_credits
       where (v_ref is not null and ref = v_ref)
          or (v_ref is null and amount = amt and received_at > now() - interval '3 minutes')
       limit 1;
      if found then
        res := 'duplicate';
      else
        select count(*) into open_n from public.payments
         where amount = amt and created_at > now() - interval '30 minutes'
           and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null));
        insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
        values (amt, v_ref, left('sms:' || snd, 80), left(t, 500), 'sms-forwarder', open_n > 1)
        returning * into c;
        target := public.find_order_for_credit(amt, v_ref, now());
        if target is not null then
          pay := public.settle_bank_credit(c.id, target);
          res := case when pay.status = 'approved' then 'activated' else 'matched: waiting for admin approval' end;
        elsif open_n > 1 then
          res := 'several orders of this amount: waiting for the customer''s UTR';
        else
          update public.payments set bank_credit_id = c.id, updated_at = now()
           where id = (select id from public.payments
                        where status = 'approved' and bank_credit_id is null and amount = amt
                          and (v_ref is null or upper(coalesce(txn_id, '')) = v_ref)
                          and created_at > now() - interval '24 hours'
                        order by created_at desc limit 1)
          returning * into pay;
          if pay.id is not null then
            update public.bank_credits set payment_id = pay.id where id = c.id;
            res := 'proof added to an active order';
          else
            res := 'no matching order';
          end if;
        end if;
      end if;
    end if;
  end if;
  insert into public.bank_sms_log (sender, result, amount, ref, payment_id) values (snd, res, amt, v_ref, pay.id);
  return json_build_object('ok', true, 'result', res, 'amount', amt, 'ref', v_ref, 'payment_id', pay.id);
end $$;
revoke execute on function public.ingest_bank_sms(text, text, text) from public, anon, authenticated;
grant execute on function public.ingest_bank_sms(text, text, text) to service_role;

-- =====================================================================
-- 33. Bank SMS amounts without a currency word ("credited by 149.00").
-- =====================================================================
create or replace function public.ingest_bank_sms(p_key text, p_sender text, p_text text)
returns json language plpgsql security definer set search_path = public as $$
declare
  s      public.bank_sms_settings;
  t      text := left(coalesce(p_text, ''), 1000);
  lt     text;
  snd    text := left(btrim(coalesce(p_sender, '')), 80);
  m      text[];
  amt    numeric(10,2);
  v_ref  text;
  c      public.bank_credits;
  pay    public.payments;
  target uuid;
  open_n int;
  res    text;
begin
  select * into s from public.bank_sms_settings where id = 1;
  if s.key_hash is null or coalesce(p_key, '') = ''
     or encode(sha256(convert_to(p_key, 'UTF8')), 'hex') <> s.key_hash then
    return json_build_object('ok', false, 'error', 'bad key');
  end if;
  lt := lower(t);
  if not s.enabled then res := 'ignored: auto-verify is off';
  elsif coalesce(btrim(s.sender_filter), '') = '' then res := 'ignored: bank sender name not set';
  elsif position(upper(btrim(s.sender_filter)) in upper(snd)) = 0 then res := 'ignored: sender does not match';
  elsif snd ~ '^\+?[0-9 ()-]+$' then res := 'ignored: sent from a phone number';
  elsif lt ~ '\mdebit' then res := 'ignored: debit message';
  elsif lt !~ '(credited|\mcr\M|received|deposited)' then res := 'ignored: not a credit message';
  else
    -- "Rs 149", "INR 149.00", "₹149" or "credited by 149.00" (no currency word, as SBI writes it)
    m := regexp_match(t, '(?:(?:rs\.?|inr|₹)\s*|(?:credited|debited|cr\.?|dr\.?)\s+(?:by|with|for)\s+(?:rs\.?\s*|inr\s*|₹\s*)?)([0-9][0-9,]*(?:\.[0-9]{1,2})?)', 'i');
    amt := case when m is null then null else nullif(replace(m[1], ',', ''), '')::numeric end;
    if amt is null or amt <= 0 then
      res := 'ignored: amount not found';
    else
      m := regexp_match(t, '(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn)[^0-9]{0,15}([0-9]{12})', 'i');
      if m is null then m := regexp_match(t, '\m([0-9]{12})\M'); end if;
      v_ref := m[1];
      select * into c from public.bank_credits
       where (v_ref is not null and ref = v_ref)
          or (v_ref is null and amount = amt and received_at > now() - interval '3 minutes')
       limit 1;
      if found then
        res := 'duplicate';
      else
        select count(*) into open_n from public.payments
         where amount = amt and created_at > now() - interval '30 minutes'
           and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null));
        insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
        values (amt, v_ref, left('sms:' || snd, 80), left(t, 500), 'sms-forwarder', open_n > 1)
        returning * into c;
        target := public.find_order_for_credit(amt, v_ref, now());
        if target is not null then
          pay := public.settle_bank_credit(c.id, target);
          res := case when pay.status = 'approved' then 'activated' else 'matched: waiting for admin approval' end;
        elsif open_n > 1 then
          res := 'several orders of this amount: waiting for the customer''s UTR';
        else
          update public.payments set bank_credit_id = c.id, updated_at = now()
           where id = (select id from public.payments
                        where status = 'approved' and bank_credit_id is null and amount = amt
                          and (v_ref is null or upper(coalesce(txn_id, '')) = v_ref)
                          and created_at > now() - interval '24 hours'
                        order by created_at desc limit 1)
          returning * into pay;
          if pay.id is not null then
            update public.bank_credits set payment_id = pay.id where id = c.id;
            res := 'proof added to an active order';
          else
            res := 'no matching order';
          end if;
        end if;
      end if;
    end if;
  end if;
  insert into public.bank_sms_log (sender, result, amount, ref, payment_id) values (snd, res, amt, v_ref, pay.id);
  return json_build_object('ok', true, 'result', res, 'amount', amt, 'ref', v_ref, 'payment_id', pay.id);
end $$;
revoke execute on function public.ingest_bank_sms(text, text, text) from public, anon, authenticated;
grant execute on function public.ingest_bank_sms(text, text, text) to service_role;

-- =====================================================================
-- 34. CUSTOMER DEBIT SMS (owner's decision)
--     After paying, the Veltora app asks the customer (Google SMS User Consent:
--     one message, one tap) to read the bank's debit SMS, e.g.
--     "A/C X3526 debited by 1.00 ... trf to AS ENTERPRISE Refno 628351826253".
--     The server checks: a debit message, my payee name, the exact order amount,
--     and a 12-digit Refno never used before.
--       ads (Meta) account  -> plan starts at once (like the UPI app's SUCCESS)
--       organic account     -> Needs approval
--     The owner's own credit SMS with the same Refno, when it arrives, is attached
--     as bank proof (or activates directly if it came first).
-- =====================================================================
alter table public.bank_sms_settings add column if not exists payee_match text;
update public.bank_sms_settings set payee_match = coalesce(payee_match, 'AS ENTERPRISE') where id = 1;

create or replace function public.report_debit_sms(p_payment_id uuid, p_text text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay   public.payments;
  t     text := left(coalesce(p_text, ''), 1000);
  lt    text := lower(left(coalesce(p_text, ''), 1000));
  payee text;
  m     text[];
  amt   numeric(10,2);
  v_ref text;
  c     public.bank_credits;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  select * into pay from public.payments where id = p_payment_id and user_id = auth.uid() for update;
  if not found then raise exception 'Order not found'; end if;
  if pay.status not in ('initiated', 'failed', 'cancelled') or pay.created_at < now() - interval '2 hours' then
    return pay;
  end if;
  select payee_match into payee from public.bank_sms_settings where id = 1;
  if lt !~ '\mdebit' then return pay; end if;
  if coalesce(btrim(payee), '') = '' or position(lower(btrim(payee)) in lt) = 0 then return pay; end if;
  m := regexp_match(t, '(?:(?:rs\.?|inr|₹)\s*|(?:credited|debited|cr\.?|dr\.?)\s+(?:by|with|for)\s+(?:rs\.?\s*|inr\s*|₹\s*)?)([0-9][0-9,]*(?:\.[0-9]{1,2})?)', 'i');
  amt := case when m is null then null else nullif(replace(m[1], ',', ''), '')::numeric end;
  if amt is distinct from pay.amount then return pay; end if;
  m := regexp_match(t, '(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn)[^0-9]{0,15}([0-9]{12})', 'i');
  if m is null then m := regexp_match(t, '\m([0-9]{12})\M'); end if;
  v_ref := m[1];
  if v_ref is null then return pay; end if;
  if exists (select 1 from public.payments
              where id <> pay.id and (upper(coalesce(txn_id, '')) = v_ref or upper(reference) = v_ref)) then
    return pay;
  end if;
  update public.payments
     set client_status = 'success', upi_response = left('debit sms: ' || t, 2000), txn_id = v_ref, updated_at = now()
   where id = pay.id
  returning * into pay;
  -- the owner's credit SMS with this Refno already arrived: that is bank proof
  select * into c from public.bank_credits where payment_id is null and bank_credits.ref = v_ref and amount = pay.amount limit 1;
  if found then return public.settle_bank_credit(c.id, pay.id); end if;
  if public.user_is_meta(pay.user_id) then
    pay := public.apply_verified_payment(pay.id, pay.amount, v_ref, null, 'upi_app', null);
    update public.payments set reviewed_by = 'auto (customer debit SMS)' where id = pay.id returning * into pay;
    return pay;
  end if;
  update public.payments
     set status = 'pending', reviewed_by = 'Customer debit SMS - waiting for admin approval', updated_at = now()
   where id = pay.id
  returning * into pay;
  return pay;
end $$;
revoke execute on function public.report_debit_sms(uuid, text) from public, anon;
grant execute on function public.report_debit_sms(uuid, text) to authenticated;

create or replace function public.admin_bank_sms_status()
returns json language plpgsql stable security definer set search_path = public as $$
declare s public.bank_sms_settings;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  select * into s from public.bank_sms_settings where id = 1;
  return json_build_object('enabled', coalesce(s.enabled, false), 'sender_filter', s.sender_filter,
                           'has_key', s.key_hash is not null, 'updated_at', s.updated_at,
                           'payee_match', s.payee_match);
end $$;

-- The receiver name the customer's bank prints on the debit SMS ("trf to AS ENTERPRISE").
create or replace function public.admin_set_payee_match(p_name text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can change this'; end if;
  update public.bank_sms_settings set payee_match = nullif(btrim(coalesce(p_name, '')), ''), updated_at = now() where id = 1;
  return public.admin_bank_sms_status();
end $$;
revoke execute on function public.admin_set_payee_match(text) from public, anon;
grant execute on function public.admin_set_payee_match(text) to authenticated;
-- Credits never jump to another customer's order: a reference already on an order decides,
-- and orders that carry a different reference are skipped by the amount fallback.
create or replace function public.find_order_for_credit(p_amount numeric, p_ref text, p_at timestamptz)
returns uuid language plpgsql stable security definer set search_path = public as $$
declare target uuid; n int; st text; has_credit boolean;
begin
  if p_ref is not null then
    -- an order already carrying this reference owns the credit (or nobody does, if it's closed)
    select id, status, bank_credit_id is not null into target, st, has_credit from public.payments
     where upper(coalesce(txn_id, '')) = p_ref or upper(reference) = p_ref
     order by created_at desc limit 1;
    if target is not null then
      return case when (st in ('initiated', 'failed', 'cancelled') or (st = 'pending' and not has_credit))
                   and (select amount from public.payments where id = target) = p_amount then target end;
    end if;
  end if;
  -- otherwise by amount, only when exactly one open order fits; orders that already carry a
  -- different reference (from the UPI app or a debit SMS) are not candidates
  select count(*), min(id::text)::uuid into n, target from public.payments
   where amount = p_amount
     and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null))
     and (p_ref is null or txn_id is null or upper(txn_id) = p_ref)
     and created_at between p_at - interval '30 minutes' and p_at + interval '2 minutes';
  return case when n = 1 then target end;
end $$;
revoke execute on function public.find_order_for_credit(numeric, text, timestamptz) from public, anon, authenticated;

create or replace function public.ingest_bank_sms(p_key text, p_sender text, p_text text)
returns json language plpgsql security definer set search_path = public as $$
declare
  s      public.bank_sms_settings;
  t      text := left(coalesce(p_text, ''), 1000);
  lt     text;
  snd    text := left(btrim(coalesce(p_sender, '')), 80);
  m      text[];
  amt    numeric(10,2);
  v_ref  text;
  c      public.bank_credits;
  pay    public.payments;
  target uuid;
  open_n int;
  res    text;
begin
  select * into s from public.bank_sms_settings where id = 1;
  if s.key_hash is null or coalesce(p_key, '') = ''
     or encode(sha256(convert_to(p_key, 'UTF8')), 'hex') <> s.key_hash then
    return json_build_object('ok', false, 'error', 'bad key');
  end if;
  lt := lower(t);
  if not s.enabled then res := 'ignored: auto-verify is off';
  elsif coalesce(btrim(s.sender_filter), '') = '' then res := 'ignored: bank sender name not set';
  elsif position(upper(btrim(s.sender_filter)) in upper(snd)) = 0 then res := 'ignored: sender does not match';
  elsif snd ~ '^\+?[0-9 ()-]+$' then res := 'ignored: sent from a phone number';
  elsif lt ~ '\mdebit' then res := 'ignored: debit message';
  elsif lt !~ '(credited|\mcr\M|received|deposited)' then res := 'ignored: not a credit message';
  else
    -- "Rs 149", "INR 149.00", "₹149" or "credited by 149.00" (no currency word, as SBI writes it)
    m := regexp_match(t, '(?:(?:rs\.?|inr|₹)\s*|(?:credited|debited|cr\.?|dr\.?)\s+(?:by|with|for)\s+(?:rs\.?\s*|inr\s*|₹\s*)?)([0-9][0-9,]*(?:\.[0-9]{1,2})?)', 'i');
    amt := case when m is null then null else nullif(replace(m[1], ',', ''), '')::numeric end;
    if amt is null or amt <= 0 then
      res := 'ignored: amount not found';
    else
      m := regexp_match(t, '(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn)[^0-9]{0,15}([0-9]{12})', 'i');
      if m is null then m := regexp_match(t, '\m([0-9]{12})\M'); end if;
      v_ref := m[1];
      select * into c from public.bank_credits
       where (v_ref is not null and ref = v_ref)
          or (v_ref is null and amount = amt and received_at > now() - interval '3 minutes')
       limit 1;
      if found then
        res := 'duplicate';
      else
        select count(*) into open_n from public.payments
         where amount = amt and created_at > now() - interval '30 minutes'
           and (status in ('initiated', 'failed', 'cancelled') or (status = 'pending' and bank_credit_id is null));
        insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
        values (amt, v_ref, left('sms:' || snd, 80), left(t, 500), 'sms-forwarder', open_n > 1)
        returning * into c;
        target := public.find_order_for_credit(amt, v_ref, now());
        if target is not null then
          pay := public.settle_bank_credit(c.id, target);
          res := case when pay.status = 'approved' then 'activated' else 'matched: waiting for admin approval' end;
        else
          -- Already activated (UPI app / customer's debit SMS)? Attach the bank proof to it.
          update public.payments set bank_credit_id = c.id, updated_at = now()
           where id = (select id from public.payments
                        where status = 'approved' and bank_credit_id is null and amount = amt
                          and (case when v_ref is not null then upper(coalesce(txn_id, '')) = v_ref
                                    else open_n = 0 end)
                          and created_at > now() - interval '24 hours'
                        order by created_at desc limit 1)
          returning * into pay;
          if pay.id is not null then
            update public.bank_credits set payment_id = pay.id where id = c.id;
            res := 'proof added to an active order';
          elsif open_n > 1 then
            res := 'several orders of this amount: waiting for the customer''s UTR';
          else
            res := 'no matching order';
          end if;
        end if;
      end if;
    end if;
  end if;
  insert into public.bank_sms_log (sender, result, amount, ref, payment_id) values (snd, res, amt, v_ref, pay.id);
  return json_build_object('ok', true, 'result', res, 'amount', amt, 'ref', v_ref, 'payment_id', pay.id);
end $$;
revoke execute on function public.ingest_bank_sms(text, text, text) from public, anon, authenticated;
grant execute on function public.ingest_bank_sms(text, text, text) to service_role;

-- =====================================================================
-- 35. UPI SUBSCRIPTION SYSTEM (Manual + Auto / YouPI-PG)
--     Premium only after a verified payment or an owner's approval.
--     Payment statuses: pending -> submitted (UTR) -> paid | rejected | expired | review.
--     Ads (Meta) accounts are activated as soon as a payment is verified (gateway webhook or
--     bank credit SMS); organic accounts always wait for the owner's approval ('review').
--     Payment pages and data are for the owner only (managers never see them).
-- =====================================================================

-- ---------- settings (single row) ----------
create table if not exists public.payment_settings (
  id               int primary key default 1 check (id = 1),
  upi_vpa          text,
  payee_name       text,
  mode             text not null default 'manual' check (mode in ('manual', 'auto')),
  youpi_base_url   text,
  youpi_public_key text,
  setup_checklist  jsonb not null default '{}'::jsonb,
  health_ok_at     timestamptz,
  health_report    jsonb,
  updated_at       timestamptz not null default now()
);
alter table public.payment_settings enable row level security;
do $$ begin
  if not exists (select 1 from pg_policies where tablename = 'payment_settings' and policyname = 'owner reads payment settings') then
    create policy "owner reads payment settings" on public.payment_settings for select using (public.is_owner() is true);
    create policy "owner edits payment settings" on public.payment_settings for update
      using (public.is_owner() is true) with check (public.is_owner() is true);
  end if;
end $$;
insert into public.payment_settings (id, upi_vpa, payee_name)
select 1, a.upi_id, coalesce(a.payee_name, 'Veltora') from public.app_settings a where a.id = 1
on conflict (id) do nothing;
insert into public.payment_settings (id) values (1) on conflict (id) do nothing;

-- Auto mode only after a passing health check for the same gateway URL (last 30 minutes).
create or replace function public.payment_settings_guard()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  new.upi_vpa          := nullif(btrim(coalesce(new.upi_vpa, '')), '');
  new.payee_name       := nullif(btrim(coalesce(new.payee_name, '')), '');
  new.youpi_base_url   := nullif(regexp_replace(btrim(coalesce(new.youpi_base_url, '')), '/+$', ''), '');
  new.youpi_public_key := nullif(btrim(coalesce(new.youpi_public_key, '')), '');
  if new.youpi_base_url is not null and new.youpi_base_url !~ '^https://' then
    raise exception 'YouPI URL must start with https://';
  end if;
  if new.upi_vpa is not null and new.upi_vpa !~ '^[A-Za-z0-9._-]{2,}@[A-Za-z0-9.-]{2,}$' then
    raise exception 'That does not look like a UPI ID (name@bank)';
  end if;
  if new.youpi_base_url is distinct from old.youpi_base_url or new.youpi_public_key is distinct from old.youpi_public_key then
    new.health_ok_at := null;
  end if;
  if new.mode = 'auto' and (old.mode <> 'auto' or new.youpi_base_url is distinct from old.youpi_base_url
                            or new.youpi_public_key is distinct from old.youpi_public_key) then
    if new.youpi_base_url is null or new.youpi_public_key is null
       or old.health_ok_at is null or old.health_ok_at < now() - interval '30 minutes'
       or (old.health_report ->> 'base_url') is distinct from new.youpi_base_url then
      raise exception 'Run "Test connection" and make sure every check passes before switching to Auto';
    end if;
  end if;
  new.updated_at := now();
  return new;
end $$;
create or replace trigger payment_settings_guard before update on public.payment_settings
  for each row execute function public.payment_settings_guard();

-- Keep the older app_settings UPI fields (read by older app versions) in step.
create or replace function public.payment_settings_sync()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_table_name = 'payment_settings' then
    update public.app_settings set upi_id = new.upi_vpa, payee_name = coalesce(new.payee_name, payee_name), updated_at = now()
     where id = 1 and (upi_id is distinct from new.upi_vpa or (new.payee_name is not null and payee_name is distinct from new.payee_name));
  else
    update public.payment_settings set upi_vpa = new.upi_id, payee_name = coalesce(new.payee_name, payee_name)
     where id = 1 and (upi_vpa is distinct from new.upi_id or (new.payee_name is not null and payee_name is distinct from new.payee_name));
  end if;
  return null;
end $$;
create or replace trigger payment_settings_sync after update on public.payment_settings
  for each row execute function public.payment_settings_sync();
create or replace trigger app_settings_upi_sync after update of upi_id, payee_name on public.app_settings
  for each row execute function public.payment_settings_sync();

-- Public checkout settings (no secrets live in this table).
create or replace function public.get_checkout_settings()
returns json language sql stable security definer set search_path = public as $$
  select json_build_object('upi_vpa', upi_vpa, 'payee_name', coalesce(payee_name, 'Veltora'), 'mode', mode,
                           'youpi_base_url', youpi_base_url, 'youpi_public_key', youpi_public_key)
    from public.payment_settings where id = 1
$$;
grant execute on function public.get_checkout_settings() to anon, authenticated;

-- Free mode is gone: premium always needs a paid subscription.
update public.app_settings set free_mode = false where id = 1 and free_mode is distinct from false;
do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'app_settings_free_mode_off') then
    alter table public.app_settings add constraint app_settings_free_mode_off check (free_mode is not true);
  end if;
end $$;

-- ---------- plans: price in paise ----------
alter table public.subscription_plans add column if not exists price_paise integer;
update public.subscription_plans set price_paise = round(price * 100)::int where price_paise is null;
create or replace function public.plans_price_sync()
returns trigger language plpgsql set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    if new.price_paise is not null then new.price := new.price_paise / 100.0;
    else new.price_paise := round(new.price * 100)::int; end if;
  elsif new.price_paise is distinct from old.price_paise then
    new.price := new.price_paise / 100.0;
  elsif new.price is distinct from old.price then
    new.price_paise := round(new.price * 100)::int;
  end if;
  if new.price_paise is null or new.price_paise < 100 then raise exception 'Price must be at least ₹1'; end if;
  return new;
end $$;
create or replace trigger plans_price_sync before insert or update on public.subscription_plans
  for each row execute function public.plans_price_sync();

-- ---------- subscriptions ----------
alter table public.subscriptions add column if not exists starts_at timestamptz;
update public.subscriptions set starts_at = coalesce(starts_at, updated_at) where starts_at is null;

-- The one access rule: a subscription that has not expired.
create or replace function public.user_has_premium(p_user uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select p_user is not null and exists (select 1 from public.subscriptions where user_id = p_user and expires_at > now())
$$;
revoke execute on function public.user_has_premium(uuid) from public, anon, authenticated;
create or replace function public.has_active_subscription()
returns boolean language sql stable security definer set search_path = public as $$
  select public.user_has_premium(auth.uid())
$$;

-- ---------- payments ----------
alter table public.payments add column if not exists order_id text;
alter table public.payments add column if not exists amount_paise integer;
alter table public.payments add column if not exists utr text;
alter table public.payments add column if not exists gateway_payment_id text;
alter table public.payments add column if not exists paid_at timestamptz;
alter table public.payments add column if not exists approved_by text;

alter table public.payments drop constraint if exists payments_status_check;
alter table public.payments drop constraint if exists payments_method_check;

update public.payments set amount_paise = round(amount * 100)::int where amount_paise is null;
update public.payments set order_id = 'CHL-' || to_char(created_at at time zone 'Asia/Kolkata', 'YYYYMMDD') || '-'
                                      || upper(substr(md5(id::text), 1, 5))
 where order_id is null;
update public.payments p set utr = upper(p.txn_id)
 where p.utr is null and p.txn_id ~ '^[0-9]{12}$'
   and not exists (select 1 from public.payments q where q.id <> p.id and upper(q.txn_id) = upper(p.txn_id) and q.created_at < p.created_at);
update public.payments set
  status = case status
             when 'initiated' then case when created_at > now() - interval '30 minutes' then 'pending' else 'expired' end
             when 'pending'   then 'submitted'
             when 'approved'  then 'paid'
             when 'revoked'   then 'rejected'
             when 'failed'    then 'expired'
             when 'cancelled' then 'expired'
             else status end,
  paid_at     = case when status = 'approved' then coalesce(verified_at, reviewed_at, updated_at) else paid_at end,
  approved_by = case when status = 'approved' then reviewed_by else approved_by end,
  method      = case when method in ('upi_manual', 'youpi') then method else 'upi_manual' end
 where status in ('initiated', 'pending', 'approved', 'revoked', 'failed', 'cancelled') or method not in ('upi_manual', 'youpi');

alter table public.payments alter column amount_paise set not null;
alter table public.payments alter column order_id set not null;
alter table public.payments add constraint payments_status_check
  check (status in ('pending', 'submitted', 'paid', 'rejected', 'expired', 'review'));
alter table public.payments add constraint payments_method_check check (method in ('upi_manual', 'youpi'));
create unique index if not exists payments_order_id_key on public.payments (order_id);
create unique index if not exists payments_utr_key on public.payments (utr) where utr is not null;
create index if not exists payments_status_created_idx on public.payments (status, created_at desc);

-- Clients never write payments directly; everything goes through the functions below.
alter policy "user inserts own pending payment" on public.payments with check (false);
alter policy "admins update payments" on public.payments using (false) with check (false);
alter policy "user reads own payments" on public.payments using (user_id = auth.uid() or public.is_owner() is true);
alter policy "user reads own subscription" on public.subscriptions using (user_id = auth.uid() or public.is_owner() is true);
alter policy "admins read bank credits" on public.bank_credits using (public.is_owner() is true);
alter policy "admins read sms log" on public.bank_sms_log using (public.is_owner() is true);
alter policy "owner manages plans" on public.subscription_plans using (public.is_owner() is true) with check (public.is_owner() is true);

create or replace function public.new_order_id()
returns text language plpgsql volatile set search_path = public as $$
declare k text; i int;
begin
  loop
    k := '';
    for i in 1..5 loop
      k := k || substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 1 + floor(random() * 32)::int, 1);
    end loop;
    k := 'CHL-' || to_char(now() at time zone 'Asia/Kolkata', 'YYYYMMDD') || '-' || k;
    exit when not exists (select 1 from public.payments where order_id = k);
  end loop;
  return k;
end $$;

-- Every new payment: amount from the plan, status pending, nothing else from the caller.
create or replace function public.payments_before_insert()
returns trigger language plpgsql security definer set search_path = public as $$
declare p public.subscription_plans;
begin
  select * into p from public.subscription_plans where id = new.plan_id and active;
  if not found then raise exception 'This plan is not available'; end if;
  if new.user_id is null then new.user_id := auth.uid(); end if;
  if new.user_id is null then raise exception 'Please sign in first'; end if;
  new.user_email   := (select email from auth.users where id = new.user_id);
  new.plan_name    := p.name;
  new.amount_paise := p.price_paise;
  new.amount       := p.price_paise / 100.0;
  new.currency     := 'INR';
  new.order_id     := public.new_order_id();
  new.reference    := new.order_id;
  new.status       := 'pending';
  new.method       := case when new.method = 'youpi' then 'youpi' else 'upi_manual' end;
  new.utr := null; new.txn_id := null; new.gateway_payment_id := null; new.upi_response := null; new.client_status := null;
  new.paid_at := null; new.approved_by := null; new.admin_note := null; new.reviewed_at := null; new.reviewed_by := null;
  new.verified_at := null; new.verification_source := null; new.bank_credit_id := null;
  new.created_at := now(); new.updated_at := now();
  return new;
end $$;

create or replace function public.payments_attribution()
returns trigger language plpgsql security definer set search_path = public as $$
declare ua public.user_attribution; had_active boolean;
begin
  if new.attribution_source is null or (new.status = 'paid' and old.status is distinct from 'paid') then
    select * into ua from public.user_attribution where user_id = new.user_id;
    new.attribution_source   := coalesce(ua.first_touch_source, 'unknown');
    new.attribution_campaign := ua.first_touch_campaign;
    new.attribution_content  := ua.first_touch_content;
  end if;
  if tg_op = 'UPDATE' and new.status = 'paid' and old.status is distinct from 'paid' then
    select public.user_has_premium(new.user_id) into had_active;
    insert into public.analytics_events (event, user_id, source, campaign, plan_id)
    values ('payment_success', new.user_id, new.attribution_source, new.attribution_campaign, new.plan_id);
    if not had_active then
      insert into public.analytics_events (event, user_id, source, campaign, plan_id)
      values ('subscription_started', new.user_id, new.attribution_source, new.attribution_campaign, new.plan_id);
    end if;
  end if;
  return new;
end $$;

-- ---------- activation (internal) ----------
-- Marks the payment paid and extends the subscription from its current expiry (or from now).
create or replace function public.activate_subscription(p_payment_id uuid, p_source text, p_approved_by text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay  public.payments;
  plan public.subscription_plans;
  cur  public.subscriptions;
  base timestamptz;
begin
  select * into pay from public.payments where id = p_payment_id for update;
  if not found then raise exception 'Order not found'; end if;
  if pay.status = 'paid' then return pay; end if;
  if pay.status not in ('pending', 'submitted', 'review', 'expired') then
    raise exception 'This payment can no longer be approved (status %)', pay.status;
  end if;
  select * into plan from public.subscription_plans where id = pay.plan_id;
  -- statuses first, so the attribution trigger sees the account before the plan is extended
  update public.payments
     set status = 'paid', paid_at = now(), approved_by = p_approved_by, verified_at = now(),
         verification_source = p_source, reviewed_at = now(), reviewed_by = p_approved_by, updated_at = now()
   where id = pay.id
  returning * into pay;
  select * into cur from public.subscriptions where user_id = pay.user_id for update;
  base := case when cur.expires_at > now() then cur.expires_at else now() end;
  insert into public.subscriptions (user_id, user_email, plan_id, status, starts_at, expires_at, updated_at)
  values (pay.user_id, pay.user_email, pay.plan_id, 'active', now(), base + public.plan_interval(plan), now())
  on conflict (user_id) do update
     set plan_id = excluded.plan_id, user_email = excluded.user_email, status = 'active',
         starts_at = case when subscriptions.expires_at > now() then subscriptions.starts_at else now() end,
         expires_at = excluded.expires_at, updated_at = now();
  return pay;
end $$;
revoke execute on function public.activate_subscription(uuid, text, text) from public, anon, authenticated;

-- A verified payment (gateway or bank credit): ads accounts start at once, organic accounts wait.
create or replace function public.verified_payment(p_payment_id uuid, p_source text, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  select * into pay from public.payments where id = p_payment_id for update;
  if pay.status = 'paid' then return pay; end if;
  if public.user_is_meta(pay.user_id) then
    return public.activate_subscription(pay.id, p_source, 'auto: ' || p_source);
  end if;
  update public.payments
     set status = 'review', verified_at = now(), verification_source = p_source,
         admin_note = coalesce(p_note, 'Payment verified - approve to start the plan'), updated_at = now()
   where id = pay.id
  returning * into pay;
  return pay;
end $$;
revoke execute on function public.verified_payment(uuid, text, text) from public, anon, authenticated;

-- ---------- customer functions ----------
create or replace function public.create_order(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments; m text; price int;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  select price_paise into price from public.subscription_plans where id = p_plan_id and active;
  if price is null then raise exception 'This plan is not available'; end if;
  select mode into m from public.payment_settings where id = 1;
  if (select upi_vpa from public.payment_settings where id = 1) is null and coalesce(m, 'manual') = 'manual' then
    raise exception 'Payments are not set up yet. Please try again later.';
  end if;
  -- reuse a fresh open order for the same plan and price
  select * into r from public.payments
   where user_id = auth.uid() and plan_id = p_plan_id and status = 'pending'
     and amount_paise = price and created_at > now() - interval '25 minutes'
   order by created_at desc limit 1;
  if found then return r; end if;
  update public.payments set status = 'expired', admin_note = 'replaced by a new order', updated_at = now()
   where user_id = auth.uid() and status = 'pending';
  insert into public.payments (plan_id, user_id, reference, status, method)
  values (p_plan_id, auth.uid(), 'new', 'pending', case when m = 'auto' then 'youpi' else 'upi_manual' end)
  returning * into r;
  return r;
end $$;
revoke execute on function public.create_order(uuid) from public, anon;
grant execute on function public.create_order(uuid) to authenticated;

-- Manual mode: the customer reports the UPI reference (UTR). A bank credit with that UTR verifies it.
create or replace function public.submit_utr(p_order_id text, p_utr text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay public.payments;
  v   text := regexp_replace(coalesce(p_utr, ''), '\s', '', 'g');
  c   public.bank_credits;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if v !~ '^[0-9]{12}$' then raise exception 'The UTR / transaction ID has 12 digits'; end if;
  select * into pay from public.payments where order_id = upper(btrim(p_order_id)) and user_id = auth.uid() for update;
  if not found then raise exception 'Order not found'; end if;
  if pay.status in ('submitted', 'review', 'paid') and pay.utr = v then return pay; end if;
  if pay.status not in ('pending', 'expired') then
    raise exception 'This order can no longer take a UTR (status %)', pay.status;
  end if;
  if exists (select 1 from public.payments where utr = v and id <> pay.id) then
    raise exception 'This UTR has already been used';
  end if;
  update public.payments set status = 'submitted', utr = v, updated_at = now() where id = pay.id returning * into pay;
  -- the owner's bank credit SMS with the same UTR already arrived: verified
  select * into c from public.bank_credits where payment_id is null and ref = v and amount = pay.amount_paise / 100.0 limit 1;
  if found then
    update public.bank_credits set payment_id = pay.id where id = c.id;
    update public.payments set bank_credit_id = c.id where id = pay.id;
    pay := public.verified_payment(pay.id, 'bank_credit', 'Bank SMS confirmed this UTR - approve to start the plan');
  end if;
  return pay;
end $$;
revoke execute on function public.submit_utr(text, text) from public, anon;
grant execute on function public.submit_utr(text, text) to authenticated;

create or replace function public.my_payment(p_order_id text)
returns public.payments language sql stable security definer set search_path = public as $$
  select * from public.payments where order_id = upper(btrim(p_order_id)) and user_id = auth.uid()
$$;
revoke execute on function public.my_payment(text) from public, anon;
grant execute on function public.my_payment(text) to authenticated;

-- ---------- gateway (service role only) ----------
create or replace function public.gateway_payment_success(p_order_id text, p_amount_paise integer, p_utr text, p_payment_id text)
returns json language plpgsql security definer set search_path = public as $$
declare
  pay public.payments;
  v   text := nullif(regexp_replace(coalesce(p_utr, ''), '\s', '', 'g'), '');
begin
  select * into pay from public.payments where order_id = upper(btrim(coalesce(p_order_id, ''))) for update;
  if not found then return json_build_object('result', 'unknown_order'); end if;
  if pay.status = 'paid' then return json_build_object('result', 'already_paid', 'order_id', pay.order_id); end if;
  if v is not null and exists (select 1 from public.payments where utr = v and id <> pay.id) then
    return json_build_object('result', 'duplicate_utr', 'order_id', pay.order_id);
  end if;
  if pay.status in ('review') and pay.gateway_payment_id is not distinct from p_payment_id then
    return json_build_object('result', 'already_review', 'order_id', pay.order_id);
  end if;
  update public.payments
     set utr = coalesce(v, utr), gateway_payment_id = coalesce(p_payment_id, gateway_payment_id), method = 'youpi', updated_at = now()
   where id = pay.id
  returning * into pay;
  if p_amount_paise is null or p_amount_paise <> pay.amount_paise then
    update public.payments
       set status = 'review', admin_note = format('Gateway reported %s paise, order is %s paise - check before approving',
                                                  coalesce(p_amount_paise::text, '?'), pay.amount_paise), updated_at = now()
     where id = pay.id;
    return json_build_object('result', 'review_amount', 'order_id', pay.order_id);
  end if;
  pay := public.verified_payment(pay.id, 'gateway', 'Paid via gateway (verified) - approve to start the plan');
  return json_build_object('result', case when pay.status = 'paid' then 'paid' else 'review' end,
                           'order_id', pay.order_id, 'payment_id', pay.id);
end $$;
revoke execute on function public.gateway_payment_success(text, integer, text, text) from public, anon, authenticated;
grant execute on function public.gateway_payment_success(text, integer, text, text) to service_role;

-- ---------- owner functions ----------
create or replace function public.admin_review_payment(p_payment_id uuid, p_approve boolean, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments; who text := coalesce(auth.jwt() ->> 'email', 'owner');
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  select * into pay from public.payments where id = p_payment_id for update;
  if not found then raise exception 'Order not found'; end if;
  if pay.status not in ('submitted', 'review') then raise exception 'Only submitted or review payments can be approved or rejected'; end if;
  if p_approve then
    pay := public.activate_subscription(pay.id, 'admin', who);
    if nullif(btrim(coalesce(p_note, '')), '') is not null then
      update public.payments set admin_note = btrim(p_note) where id = pay.id returning * into pay;
    end if;
  else
    update public.payments
       set status = 'rejected', admin_note = nullif(btrim(coalesce(p_note, '')), ''), approved_by = who,
           reviewed_at = now(), reviewed_by = who, updated_at = now()
     where id = pay.id
    returning * into pay;
  end if;
  return pay;
end $$;
revoke execute on function public.admin_review_payment(uuid, boolean, text) from public, anon;
grant execute on function public.admin_review_payment(uuid, boolean, text) to authenticated;

create or replace function public.admin_payments(p_status text, p_search text, p_limit integer)
returns json language plpgsql stable security definer set search_path = public as $$
declare q text := nullif(btrim(coalesce(p_search, '')), '');
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  return coalesce((select json_agg(x) from (
    select p.id, p.order_id, p.user_id, p.user_email email, u.phone, p.plan_id, p.plan_name, p.amount_paise, p.status,
           p.utr, p.method, p.gateway_payment_id, p.created_at, p.paid_at, p.approved_by, p.admin_note,
           p.attribution_source, p.bank_credit_id is not null bank_verified, p.verification_source
      from public.payments p left join auth.users u on u.id = p.user_id
     where (p_status is null or p.status = p_status)
       and (q is null or p.utr ilike '%' || q || '%' or p.order_id ilike '%' || q || '%'
            or p.user_email ilike '%' || q || '%' or coalesce(u.phone, '') ilike '%' || q || '%')
     order by (p.status in ('submitted', 'review')) desc, p.created_at desc
     limit least(greatest(coalesce(p_limit, 200), 1), 500)) x), '[]');
end $$;
revoke execute on function public.admin_payments(text, text, integer) from public, anon;
grant execute on function public.admin_payments(text, text, integer) to authenticated;

create table if not exists public.subscription_adjustments (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  days        integer not null,
  reason      text not null,
  old_expires timestamptz,
  new_expires timestamptz not null,
  admin_email text,
  created_at  timestamptz not null default now()
);
alter table public.subscription_adjustments enable row level security;
do $$ begin
  if not exists (select 1 from pg_policies where tablename = 'subscription_adjustments' and policyname = 'owner reads adjustments') then
    create policy "owner reads adjustments" on public.subscription_adjustments for select using (public.is_owner() is true);
  end if;
end $$;

create or replace function public.admin_extend_subscription(p_user_id uuid, p_days integer, p_reason text)
returns public.subscriptions language plpgsql security definer set search_path = public as $$
declare cur public.subscriptions; base timestamptz; s public.subscriptions;
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  if p_days is null or p_days < 1 or p_days > 3650 then raise exception 'Days must be between 1 and 3650'; end if;
  if coalesce(length(btrim(p_reason)), 0) < 3 then raise exception 'Please write a reason'; end if;
  select * into cur from public.subscriptions where user_id = p_user_id for update;
  if not found then raise exception 'This user has no subscription yet'; end if;
  base := case when cur.expires_at > now() then cur.expires_at else now() end;
  update public.subscriptions
     set expires_at = base + make_interval(days => p_days), status = 'active',
         starts_at = case when cur.expires_at > now() then starts_at else now() end, updated_at = now()
   where user_id = p_user_id
  returning * into s;
  insert into public.subscription_adjustments (user_id, days, reason, old_expires, new_expires, admin_email)
  values (p_user_id, p_days, btrim(p_reason), cur.expires_at, s.expires_at, auth.jwt() ->> 'email');
  return s;
end $$;
revoke execute on function public.admin_extend_subscription(uuid, integer, text) from public, anon;
grant execute on function public.admin_extend_subscription(uuid, integer, text) to authenticated;

create or replace function public.admin_subscribers(p_search text, p_active_only boolean)
returns json language plpgsql stable security definer set search_path = public as $$
declare q text := nullif(btrim(coalesce(p_search, '')), '');
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  return coalesce((select json_agg(x order by x.expires_at desc) from (
    select s.user_id, coalesce(s.user_email, u.email) email, u.phone, sp.name plan_name, s.starts_at, s.expires_at,
           s.expires_at > now() active,
           (select json_agg(json_build_object('days', a.days, 'reason', a.reason, 'at', a.created_at, 'by', a.admin_email)
                            order by a.created_at desc)
              from public.subscription_adjustments a where a.user_id = s.user_id) adjustments
      from public.subscriptions s
      left join auth.users u on u.id = s.user_id
      left join public.subscription_plans sp on sp.id = s.plan_id
     where (not coalesce(p_active_only, false) or s.expires_at > now())
       and (q is null or coalesce(s.user_email, u.email) ilike '%' || q || '%' or coalesce(u.phone, '') ilike '%' || q || '%')
     limit 1000) x), '[]');
end $$;
revoke execute on function public.admin_subscribers(text, boolean) from public, anon;
grant execute on function public.admin_subscribers(text, boolean) to authenticated;

create or replace function public.admin_revenue_summary()
returns json language plpgsql stable security definer set search_path = public as $$
declare today timestamptz := date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata';
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  return json_build_object(
    'today_paise',  (select coalesce(sum(amount_paise), 0) from public.payments where status = 'paid' and paid_at >= today),
    'last7_paise',  (select coalesce(sum(amount_paise), 0) from public.payments where status = 'paid' and paid_at >= now() - interval '7 days'),
    'last30_paise', (select coalesce(sum(amount_paise), 0) from public.payments where status = 'paid' and paid_at >= now() - interval '30 days'),
    'active_subscribers', (select count(*) from public.subscriptions where expires_at > now()),
    'expiring_7_days',    (select count(*) from public.subscriptions where expires_at > now() and expires_at <= now() + interval '7 days'),
    'waiting_approval',   (select count(*) from public.payments where status in ('submitted', 'review')));
end $$;
revoke execute on function public.admin_revenue_summary() from public, anon;
grant execute on function public.admin_revenue_summary() to authenticated;

create or replace function public.admin_set_setup_checklist(p_checklist jsonb)
returns json language plpgsql security definer set search_path = public as $$
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  update public.payment_settings set setup_checklist = coalesce(p_checklist, '{}'::jsonb) where id = 1;
  return (select setup_checklist::json from public.payment_settings where id = 1);
end $$;
revoke execute on function public.admin_set_setup_checklist(jsonb) from public, anon;
grant execute on function public.admin_set_setup_checklist(jsonb) to authenticated;

-- ---------- scheduled ----------
create or replace function public.expire_pending_payments()
returns integer language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.payments set status = 'expired', updated_at = now()
   where status = 'pending' and created_at < now() - interval '30 minutes';
  get diagnostics n = row_count;
  update public.subscriptions set status = 'expired', updated_at = now() where status = 'active' and expires_at <= now();
  return n;
end $$;
revoke execute on function public.expire_pending_payments() from public, anon, authenticated;

create or replace function public.expire_subscriptions()
returns integer language plpgsql security definer set search_path = public as $$
declare n int;
begin
  with expired as (
    update public.subscriptions set status = 'expired', updated_at = now()
     where status = 'active' and expires_at <= now()
    returning user_id, plan_id)
  insert into public.analytics_events (event, user_id, source, campaign, plan_id)
  select 'subscription_expired', e.user_id, coalesce(ua.first_touch_source, 'unknown'), ua.first_touch_campaign, e.plan_id
    from expired e left join public.user_attribution ua on ua.user_id = e.user_id;
  get diagnostics n = row_count;
  perform public.expire_pending_payments();
  return n;
end $$;

-- ---------- bank SMS matching on the new statuses ----------
create or replace function public.find_order_for_credit(p_amount numeric, p_ref text, p_at timestamptz)
returns uuid language plpgsql stable security definer set search_path = public as $$
declare target uuid; n int; st text; has_credit boolean; amt int := round(p_amount * 100)::int;
begin
  if p_ref is not null then
    select id, status, bank_credit_id is not null into target, st, has_credit from public.payments
     where utr = p_ref or upper(coalesce(txn_id, '')) = p_ref
     order by created_at desc limit 1;
    if target is not null then
      return case when st in ('pending', 'submitted', 'expired') and not has_credit
                   and (select amount_paise from public.payments where id = target) = amt then target end;
    end if;
  end if;
  select count(*), min(id::text)::uuid into n, target from public.payments
   where amount_paise = amt and status in ('pending', 'submitted', 'expired') and bank_credit_id is null
     and (p_ref is null or utr is null or utr = p_ref)
     and created_at between p_at - interval '30 minutes' and p_at + interval '2 minutes';
  return case when n = 1 then target end;
end $$;
revoke execute on function public.find_order_for_credit(numeric, text, timestamptz) from public, anon, authenticated;

create or replace function public.settle_bank_credit(p_credit uuid, p_payment uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare c public.bank_credits; pay public.payments;
begin
  select * into c from public.bank_credits where id = p_credit for update;
  select * into pay from public.payments where id = p_payment for update;
  if c.id is null or pay.id is null or c.payment_id is not null or round(c.amount * 100)::int <> pay.amount_paise then return pay; end if;
  update public.bank_credits set payment_id = pay.id where id = c.id;
  update public.payments
     set bank_credit_id = c.id,
         utr = coalesce(utr, case when c.ref is not null and not exists (select 1 from public.payments q where q.utr = c.ref) then c.ref end),
         updated_at = now()
   where id = pay.id;
  return public.verified_payment(pay.id, 'bank_credit', 'Bank SMS confirmed this payment - approve to start the plan');
end $$;
revoke execute on function public.settle_bank_credit(uuid, uuid) from public, anon, authenticated;

create or replace function public.try_verify_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments; c public.bank_credits;
begin
  select * into pay from public.payments where id = p_payment_id;
  if not found then return pay; end if;
  if pay.status not in ('pending', 'submitted', 'expired') or pay.bank_credit_id is not null then return pay; end if;
  for c in select * from public.bank_credits
            where payment_id is null and round(amount * 100)::int = pay.amount_paise
              and received_at >= pay.created_at - interval '2 minutes'
              and received_at <= pay.created_at + interval '48 hours'
            order by received_at loop
    if public.find_order_for_credit(c.amount, c.ref, c.received_at) = pay.id then
      return public.settle_bank_credit(c.id, pay.id);
    end if;
  end loop;
  return pay;
end $$;
revoke execute on function public.try_verify_payment(uuid) from public, anon, authenticated;

create or replace function public.check_payment_status(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  select * into pay from public.payments where id = p_payment_id and user_id = auth.uid();
  if not found then raise exception 'Order not found'; end if;
  return public.try_verify_payment(pay.id);
end $$;

create or replace function public.ingest_bank_sms(p_key text, p_sender text, p_text text)
returns json language plpgsql security definer set search_path = public as $$
declare
  s      public.bank_sms_settings;
  t      text := left(coalesce(p_text, ''), 1000);
  lt     text;
  snd    text := left(btrim(coalesce(p_sender, '')), 80);
  m      text[];
  amt    numeric(10,2);
  v_ref  text;
  c      public.bank_credits;
  pay    public.payments;
  target uuid;
  open_n int;
  res    text;
begin
  select * into s from public.bank_sms_settings where id = 1;
  if s.key_hash is null or coalesce(p_key, '') = ''
     or encode(sha256(convert_to(p_key, 'UTF8')), 'hex') <> s.key_hash then
    return json_build_object('ok', false, 'error', 'bad key');
  end if;
  lt := lower(t);
  if not s.enabled then res := 'ignored: auto-verify is off';
  elsif coalesce(btrim(s.sender_filter), '') = '' then res := 'ignored: bank sender name not set';
  elsif position(upper(btrim(s.sender_filter)) in upper(snd)) = 0 then res := 'ignored: sender does not match';
  elsif snd ~ '^\+?[0-9 ()-]+$' then res := 'ignored: sent from a phone number';
  elsif lt ~ '\mdebit' then res := 'ignored: debit message';
  elsif lt !~ '(credited|\mcr\M|received|deposited)' then res := 'ignored: not a credit message';
  else
    m := regexp_match(t, '(?:(?:rs\.?|inr|₹)\s*|(?:credited|debited|cr\.?|dr\.?)\s+(?:by|with|for)\s+(?:rs\.?\s*|inr\s*|₹\s*)?)([0-9][0-9,]*(?:\.[0-9]{1,2})?)', 'i');
    amt := case when m is null then null else nullif(replace(m[1], ',', ''), '')::numeric end;
    if amt is null or amt <= 0 then
      res := 'ignored: amount not found';
    else
      m := regexp_match(t, '(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn)[^0-9]{0,15}([0-9]{12})', 'i');
      if m is null then m := regexp_match(t, '\m([0-9]{12})\M'); end if;
      v_ref := m[1];
      select * into c from public.bank_credits
       where (v_ref is not null and ref = v_ref)
          or (v_ref is null and amount = amt and received_at > now() - interval '3 minutes')
       limit 1;
      if found then
        res := 'duplicate';
      else
        select count(*) into open_n from public.payments
         where amount_paise = round(amt * 100)::int and created_at > now() - interval '30 minutes'
           and status in ('pending', 'submitted', 'expired') and bank_credit_id is null;
        insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
        values (amt, v_ref, left('sms:' || snd, 80), left(t, 500), 'sms-forwarder', open_n > 1)
        returning * into c;
        target := public.find_order_for_credit(amt, v_ref, now());
        if target is not null then
          pay := public.settle_bank_credit(c.id, target);
          res := case when pay.status = 'paid' then 'activated' else 'matched: waiting for admin approval' end;
        else
          update public.payments set bank_credit_id = c.id, updated_at = now()
           where id = (select id from public.payments
                        where status in ('paid', 'review') and bank_credit_id is null and amount_paise = round(amt * 100)::int
                          and (case when v_ref is not null then utr = v_ref else open_n = 0 end)
                          and created_at > now() - interval '24 hours'
                        order by created_at desc limit 1)
          returning * into pay;
          if pay.id is not null then
            update public.bank_credits set payment_id = pay.id where id = c.id;
            res := 'proof added to an existing order';
          elsif open_n > 1 then
            res := 'several orders of this amount: waiting for the customer''s UTR';
          else
            res := 'no matching order';
          end if;
        end if;
      end if;
    end if;
  end if;
  insert into public.bank_sms_log (sender, result, amount, ref, payment_id) values (snd, res, amt, v_ref, pay.id);
  return json_build_object('ok', true, 'result', res, 'amount', amt, 'ref', v_ref, 'payment_id', pay.id,
                           'order_id', pay.order_id, 'status', pay.status);
end $$;
revoke execute on function public.ingest_bank_sms(text, text, text) from public, anon, authenticated;
grant execute on function public.ingest_bank_sms(text, text, text) to service_role;

-- ---------- older app versions: same names, safe behaviour ----------
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language sql security definer set search_path = public as $$
  select * from public.create_order(p_plan_id)
$$;

-- The UPI app's answer is only a hint: a 12-digit reference becomes the UTR (status submitted).
create or replace function public.report_upi_result(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  f   jsonb := public.parse_upi_response(p_response);
  st  text := lower(coalesce(f ->> 'status', ''));
  v   text;
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments set upi_response = left(p_response, 2000), client_status = nullif(st, ''), updated_at = now()
   where id = p_payment_id and user_id = auth.uid()
  returning * into pay;
  if not found then raise exception 'Order not found'; end if;
  if st = 'success' and pay.status in ('pending', 'expired') then
    v := regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g');
    if v ~ '^[0-9]{12}$' and not exists (select 1 from public.payments where utr = v and id <> pay.id) then
      return public.submit_utr(pay.order_id, v);
    end if;
  end if;
  return public.try_verify_payment(pay.id);
end $$;

-- The customer's bank debit SMS (Android one-tap consent / website paste): fills the UTR only.
create or replace function public.report_debit_sms(p_payment_id uuid, p_text text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay   public.payments;
  t     text := left(coalesce(p_text, ''), 1000);
  lt    text := lower(left(coalesce(p_text, ''), 1000));
  payee text;
  m     text[];
  amt   numeric(10,2);
  v     text;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  select * into pay from public.payments where id = p_payment_id and user_id = auth.uid();
  if not found then raise exception 'Order not found'; end if;
  if pay.status not in ('pending', 'expired') then return pay; end if;
  select payee_match into payee from public.bank_sms_settings where id = 1;
  if lt !~ '\mdebit' then return pay; end if;
  if coalesce(btrim(payee), '') <> '' and position(lower(btrim(payee)) in lt) = 0 then return pay; end if;
  m := regexp_match(t, '(?:(?:rs\.?|inr|₹)\s*|(?:credited|debited|cr\.?|dr\.?)\s+(?:by|with|for)\s+(?:rs\.?\s*|inr\s*|₹\s*)?)([0-9][0-9,]*(?:\.[0-9]{1,2})?)', 'i');
  amt := case when m is null then null else nullif(replace(m[1], ',', ''), '')::numeric end;
  if amt is null or round(amt * 100)::int <> pay.amount_paise then return pay; end if;
  m := regexp_match(t, '(?:upi\s*ref(?:erence)?|ref(?:erence)?|rrn|utr|txn)[^0-9]{0,15}([0-9]{12})', 'i');
  if m is null then m := regexp_match(t, '\m([0-9]{12})\M'); end if;
  v := m[1];
  if v is null or exists (select 1 from public.payments where utr = v and id <> pay.id) then return pay; end if;
  update public.payments set upi_response = left('debit sms: ' || t, 2000), client_status = 'success' where id = pay.id;
  return public.submit_utr(pay.order_id, v);
end $$;

-- Android "Enter UTR": reuses the customer's open order for that plan (or opens one).
create or replace function public.submit_payment(p_plan_id uuid, p_reference text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  select * into pay from public.payments
   where user_id = auth.uid() and plan_id = p_plan_id and status in ('pending', 'expired') and utr is null
   order by created_at desc limit 1;
  if not found then pay := public.create_order(p_plan_id); end if;
  return public.submit_utr(pay.order_id, p_reference);
end $$;

create or replace function public.cancel_upi_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments set status = 'expired', admin_note = 'cancelled by customer', updated_at = now()
   where id = p_payment_id and user_id = auth.uid() and status = 'pending'
  returning * into pay;
  if not found then select * into pay from public.payments where id = p_payment_id and user_id = auth.uid(); end if;
  return pay;
end $$;

create or replace function public.approve_payment(p_payment_id uuid)
returns public.payments language sql security definer set search_path = public as $$
  select * from public.admin_review_payment(p_payment_id, true, null)
$$;
create or replace function public.reject_payment(p_payment_id uuid, p_note text)
returns public.payments language sql security definer set search_path = public as $$
  select * from public.admin_review_payment(p_payment_id, false, p_note)
$$;
-- Removed actions (kept as names so older apps get a clear message).
create or replace function public.revoke_payment(p_payment_id uuid, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
begin raise exception 'Revoke was removed. Use Subscribers to adjust a plan.'; end $$;
create or replace function public.admin_verify_payment(p_payment_id uuid, p_utr text)
returns public.payments language plpgsql security definer set search_path = public as $$
begin raise exception 'Ask the customer to submit the UTR, then approve it in Payments.'; end $$;
revoke execute on function public.record_bank_credit(numeric, text, text, text) from public, anon, authenticated;
revoke execute on function public.apply_verified_payment(uuid, numeric, text, text, text, uuid) from public, anon, authenticated, service_role;
revoke execute on function public.grant_plan_for_payment(public.payments) from public, anon, authenticated;

-- ---------- reports on the new statuses (owner only) ----------
create or replace function public.claim_meta_purchases()
returns table(payment_id uuid, amount numeric, currency text, plan_name text)
language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid();
begin
  if uid is null then return; end if;
  return query
    with fresh as (
      insert into public.meta_logged (user_id, key)
      select uid, 'purchase:' || p.id from public.payments p
       where p.user_id = uid and p.status = 'paid' and p.paid_at > now() - interval '7 days'
      on conflict do nothing
      returning key)
    select p.id, p.amount_paise / 100.0, 'INR'::text, p.plan_name
      from fresh f join public.payments p on 'purchase:' || p.id = f.key;
end $$;

-- Server-side Purchase (edge functions): claims the payment once; returns what to send.
create or replace function public.claim_meta_purchase_server(p_payment_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare pay public.payments; k text;
begin
  select * into pay from public.payments where id = p_payment_id and status = 'paid';
  if not found then return null; end if;
  insert into public.meta_logged (user_id, key) values (pay.user_id, 'purchase:' || pay.id)
  on conflict do nothing returning key into k;
  if k is null then return null; end if;
  return json_build_object('order_id', pay.order_id, 'user_id', pay.user_id, 'email', pay.user_email,
                           'value', pay.amount_paise / 100.0, 'currency', 'INR', 'plan_name', pay.plan_name);
end $$;
revoke execute on function public.claim_meta_purchase_server(uuid) from public, anon, authenticated;
grant execute on function public.claim_meta_purchase_server(uuid) to service_role;

create or replace function public.admin_stats()
returns json language plpgsql security definer set search_path = public as $$
declare own boolean := public.is_owner() is true;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  perform public.expire_subscriptions();
  return json_build_object(
    'total_videos',        (select count(*) from public.titles),
    'exclusive_videos',    (select count(*) from public.titles where tier = 'hidden_premium'),
    'total_views',         (select coalesce(sum(view_count), 0) from public.titles),
    'preview_views',       (select coalesce(sum(preview_count), 0) from public.titles),
    'paying_subscribers',  case when own then (select count(*) from public.subscriptions where expires_at > now()) end,
    'expired_subscribers', case when own then (select count(*) from public.subscriptions where expires_at <= now()) end,
    'pending_payments',    case when own then (select count(*) from public.payments where status in ('submitted', 'review')) end);
end $$;

create or replace function public.admin_attribution_report(p_from timestamptz, p_to timestamptz, p_source text, p_campaign text, p_plan uuid)
returns json language plpgsql stable security definer set search_path = public as $$
declare
  f timestamptz := coalesce(p_from, '-infinity');
  t timestamptz := coalesce(p_to, 'infinity');
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  return (
    with
    inst as (
      select source, campaign, count(*) n from public.attribution_installs
       where created_at >= f and created_at < t
         and (p_source is null or source = p_source)
         and (p_campaign is null or campaign = p_campaign)
       group by 1, 2),
    regs as (
      select coalesce(ua.first_touch_source, 'unknown') source, ua.first_touch_campaign campaign, count(*) n
        from auth.users u left join public.user_attribution ua on ua.user_id = u.id
       where u.created_at >= f and u.created_at < t
         and (p_source is null or coalesce(ua.first_touch_source, 'unknown') = p_source)
         and (p_campaign is null or ua.first_touch_campaign = p_campaign)
       group by 1, 2),
    pays as (
      select coalesce(p.attribution_source, ua.first_touch_source, 'unknown') source,
             case when p.attribution_source is not null then p.attribution_campaign else ua.first_touch_campaign end campaign,
             count(distinct p.user_id) buyers, count(*) purchases, coalesce(sum(p.amount_paise), 0) / 100.0 revenue
        from public.payments p left join public.user_attribution ua on ua.user_id = p.user_id
       where p.status = 'paid' and coalesce(p.paid_at, p.created_at) >= f and coalesce(p.paid_at, p.created_at) < t
         and (p_plan is null or p.plan_id = p_plan)
         and (p_source is null or coalesce(p.attribution_source, ua.first_touch_source, 'unknown') = p_source)
         and (p_campaign is null or coalesce(p.attribution_campaign, ua.first_touch_campaign) = p_campaign)
       group by 1, 2),
    keys as (select source, campaign from inst union select source, campaign from regs union select source, campaign from pays),
    all_rows as (
      select k.source, k.campaign,
             coalesce(i.n, 0) installs, coalesce(r.n, 0) registrations,
             coalesce(p.buyers, 0) purchasers, coalesce(p.purchases, 0) purchases, coalesce(p.revenue, 0) revenue
        from keys k
        left join inst i on i.source = k.source and i.campaign is not distinct from k.campaign
        left join regs r on r.source = k.source and r.campaign is not distinct from k.campaign
        left join pays p on p.source = k.source and p.campaign is not distinct from k.campaign)
    select json_build_object(
      'by_source', coalesce((select json_agg(x order by x.revenue desc, x.registrations desc) from (
          select source, sum(installs) installs, sum(registrations) registrations, sum(purchasers) purchasers,
                 sum(purchases) purchases, sum(revenue) revenue
            from all_rows group by source) x), '[]'),
      'campaigns', coalesce((select json_agg(x order by x.revenue desc, x.registrations desc, x.installs desc) from (
          select * from all_rows where campaign is not null) x), '[]'))
  );
end $$;

create or replace function public.admin_attribution_users(p_from timestamptz, p_to timestamptz, p_source text, p_campaign text, p_purchased boolean, p_plan uuid, p_search text, p_limit integer)
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  return coalesce((select json_agg(x order by x.registered_at desc) from (
    select u.id user_id, u.email, u.created_at registered_at,
           coalesce(ua.first_touch_source, 'unknown') source, ua.first_touch_campaign campaign,
           ua.first_touch_content content, ua.first_touch_at, ua.last_touch_source, ua.last_touch_at,
           case when s.expires_at > now() then 'active' when s.user_id is not null then 'expired' end sub_status,
           s.expires_at, sp.name plan_name,
           coalesce(pay.revenue, 0) revenue, coalesce(pay.purchases, 0) purchases
      from auth.users u
      left join public.user_attribution ua on ua.user_id = u.id
      left join public.subscriptions s on s.user_id = u.id
      left join public.subscription_plans sp on sp.id = s.plan_id
      left join lateral (
        select sum(amount_paise) / 100.0 revenue, count(*) purchases from public.payments p
         where p.user_id = u.id and p.status = 'paid' and (p_plan is null or p.plan_id = p_plan)) pay on true
     where u.created_at >= coalesce(p_from, '-infinity') and u.created_at < coalesce(p_to, 'infinity')
       and (p_source is null or coalesce(ua.first_touch_source, 'unknown') = p_source)
       and (p_campaign is null or ua.first_touch_campaign = p_campaign)
       and (p_purchased is null or (coalesce(pay.purchases, 0) > 0) = p_purchased)
       and (p_search is null or u.email ilike '%' || p_search || '%')
     order by u.created_at desc
     limit least(greatest(coalesce(p_limit, 100), 1), 500)) x), '[]');
end $$;

create or replace function public.admin_user_detail(p_user_id uuid)
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if public.is_owner() is distinct from true then raise exception 'Only the owner can do this'; end if;
  return (
    select json_build_object(
      'user_id', u.id, 'email', u.email, 'registered_at', u.created_at,
      'attribution', (select row_to_json(ua) from public.user_attribution ua where ua.user_id = u.id),
      'subscription', (select json_build_object('status', case when s.expires_at > now() then 'active' else 'expired' end,
                                                'expires_at', s.expires_at, 'plan', sp.name)
                         from public.subscriptions s left join public.subscription_plans sp on sp.id = s.plan_id
                        where s.user_id = u.id),
      'payments', coalesce((select json_agg(json_build_object(
                     'id', p.id, 'plan', p.plan_name, 'amount', p.amount_paise / 100.0, 'status', p.status, 'method', p.method,
                     'utr', coalesce(p.utr, p.order_id), 'created_at', p.created_at, 'reviewed_at', p.paid_at,
                     'attribution_source', p.attribution_source, 'attribution_campaign', p.attribution_campaign)
                     order by p.created_at desc)
                   from public.payments p where p.user_id = u.id and p.status <> 'pending'), '[]'),
      'revenue', (select coalesce(sum(amount_paise), 0) / 100.0 from public.payments where user_id = u.id and status = 'paid'))
    from auth.users u where u.id = p_user_id);
end $$;

-- every 15 minutes: close unpaid orders older than 30 minutes, mark ended plans
do $$ begin
  perform cron.schedule('veltora-expire-pending', '*/15 * * * *', 'select public.expire_subscriptions()');
exception when undefined_function or invalid_schema_name then
  raise notice 'pg_cron not available here';
end $$;
