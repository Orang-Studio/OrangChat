use chrono::NaiveDateTime;

pub fn iso(dt: NaiveDateTime) -> String {
    dt.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

pub fn iso_opt(dt: Option<NaiveDateTime>) -> Option<String> {
    dt.map(iso)
}
