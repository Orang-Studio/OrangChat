use chrono::NaiveDateTime;

/// Format like JS `Date.toISOString()`: `2026-07-14T12:34:56.789Z` (always 3 ms digits).
/// Prisma stores TIMESTAMP(3) values in UTC, so a bare Z suffix is correct.
pub fn iso(dt: NaiveDateTime) -> String {
    dt.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

pub fn iso_opt(dt: Option<NaiveDateTime>) -> Option<String> {
    dt.map(iso)
}
