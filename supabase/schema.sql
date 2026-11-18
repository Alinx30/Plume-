-- Plume : tables de synchronisation.
-- À coller une seule fois dans Supabase > SQL Editor, puis cliquer « Run ».

create table if not exists public.folders (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  id text not null,
  data jsonb not null,
  updated_at bigint not null,
  deleted boolean not null default false,
  primary key (user_id, id)
);

create table if not exists public.notes (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  id text not null,
  meta jsonb not null,
  content jsonb,
  updated_at bigint not null,
  deleted boolean not null default false,
  primary key (user_id, id)
);

-- Chacun ne voit et ne modifie que ses propres notes.
alter table public.folders enable row level security;
alter table public.notes enable row level security;

drop policy if exists "plume_own_folders" on public.folders;
create policy "plume_own_folders" on public.folders for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "plume_own_notes" on public.notes;
create policy "plume_own_notes" on public.notes for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Fichiers joints (PDF, photos, audio) : un dossier par utilisateur.
insert into storage.buckets (id, name, public)
values ('assets', 'assets', false)
on conflict (id) do nothing;

drop policy if exists "plume_assets_select" on storage.objects;
create policy "plume_assets_select" on storage.objects for select to authenticated
  using (bucket_id = 'assets' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "plume_assets_insert" on storage.objects;
create policy "plume_assets_insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'assets' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "plume_assets_update" on storage.objects;
create policy "plume_assets_update" on storage.objects for update to authenticated
  using (bucket_id = 'assets' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "plume_assets_delete" on storage.objects;
create policy "plume_assets_delete" on storage.objects for delete to authenticated
  using (bucket_id = 'assets' and (storage.foldername(name))[1] = auth.uid()::text);
