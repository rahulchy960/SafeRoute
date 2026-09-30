-- SPDX-License-Identifier: AGPL-3.0-only
-- Custom migration (drizzle-kit generate --custom). PostGIS provides the geometry/geography types
-- and spatial functions (ST_DWithin, ST_Buffer, ST_Transform). UUID defaults use the built-in
-- gen_random_uuid(), so no other extension is needed (ADR 0003).
CREATE EXTENSION IF NOT EXISTS postgis;
