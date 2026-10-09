-- =====================================================================
-- STREAMS — complete database setup
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
insert into public.app_settings (upi_id, payee_name) values ('2728412a@bandhan', 'Streams');

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
insert into public.admin_emails (email, role) values (lower('YOUR_OWNER_EMAIL@example.com'), 'owner');

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
