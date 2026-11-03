INSERT INTO storage.buckets (id, min_file_size, max_file_size, allowed_mimetypes, download_expiration, cache_control)
VALUES
  ('default', 0, 52428800, NULL, 30, 'max-age=3600'),
  ('images', 0, 20971520, ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif'], 30, 'max-age=3600'),
  ('videos', 0, 104857600, ARRAY['video/mp4', 'video/quicktime', 'video/3gpp', 'video/webm', 'video/x-matroska'], 30, 'max-age=3600'),
  ('audios', 0, 31457280, ARRAY['audio/m4a', 'audio/mp4', 'audio/aac', 'audio/ogg', 'audio/wav', 'audio/mpeg', 'audio/webm'], 30, 'max-age=3600'),
  ('attachments', 0, 52428800, NULL, 30, 'max-age=3600'),
  ('avatars', 0, 10485760, ARRAY['image/jpeg', 'image/png', 'image/webp'], 30, 'max-age=3600')

ON CONFLICT (id) DO UPDATE SET
  min_file_size = EXCLUDED.min_file_size,
  max_file_size = EXCLUDED.max_file_size,
  allowed_mimetypes = EXCLUDED.allowed_mimetypes,
  download_expiration = EXCLUDED.download_expiration,
  cache_control = EXCLUDED.cache_control;
GRANT USAGE ON SCHEMA storage TO public;
GRANT ALL ON ALL TABLES IN SCHEMA storage TO public;
GRANT ALL ON ALL SEQUENCES IN SCHEMA storage TO public;
GRANT ALL ON ALL ROUTINES IN SCHEMA storage TO public;

ALTER DEFAULT PRIVILEGES IN SCHEMA storage GRANT ALL ON TABLES TO public;
ALTER DEFAULT PRIVILEGES IN SCHEMA storage GRANT ALL ON SEQUENCES TO public;

-- ------------------------------------------------------------------------------
-- STEP 3: Hasura Storage Permissions Configuration Guide
-- ------------------------------------------------------------------------------
-- Nhost Storage uses Hasura GraphQL Engine permissions to authorize upload/download.
-- When uploading without a custom JWT, Hasura assigns the "public" role.
-- 
-- To enable uploads & downloads for the app:
-- 1. In Hasura Console (Data -> storage -> files -> Permissions):
--    a. For role "public":
--       - Insert: Without any checks
--         -> Check all Column permissions: id, bucket_id, name, size, mime_type, etag, is_uploaded
--       - Select: Without any checks
--         -> Check all Column permissions: id, bucket_id, name, size, mime_type, etag, is_uploaded
--    b. For role "user":
--       - Insert: Without any checks (all columns)
--       - Select: Without any checks (all columns)
-- 
-- 2. In Hasura Console (Data -> storage -> buckets -> Permissions):
--    a. For role "public":
--       - Select: Without any checks (all columns)
--    b. For role "user":
--       - Select: Without any checks (all columns)
-- 
-- 3. (Alternative Fast Setup): If you have the Hasura Admin Secret,
--    set NHOST_ADMIN_SECRET in your .env or AI Studio Secrets panel.
--    The app will automatically authenticate all storage requests directly.
-- ==============================================================================
