//! Which client builds may still talk to this server, and how loudly each one
//! is told to update.
//!
//! The severity a client shows is decided here rather than in the client,
//! because a build old enough to be dangerous is also a build that cannot be
//! trusted to enforce its own retirement. `required` is therefore backed by an
//! actual 426 from the middleware in http.rs - the wall the client draws is a
//! courtesy on top of a refusal that has already happened.

use serde::Serialize;

/// How hard a client should push the update on the user.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    /// Up to date. Nothing to show.
    None,
    /// A newer build exists. Mention it once, dismissible, never in the way.
    Optional,
    /// Old enough to be missing fixes. Prompt on launch, still dismissible.
    Recommended,
    /// Below the minimum this server accepts. The API is already refusing this
    /// client; the UI shows a wall that cannot be dismissed.
    Required,
}

/// A client build, compared component-wise.
///
/// Android reports a monotonic `versionCode` ("47") and the desktop shell a
/// semver ("0.1.5"); both reduce to a list of numbers that only ever needs to
/// be compared against others from the same platform, so one representation
/// covers both. Non-numeric suffixes ("0.2.0-beta") are ignored rather than
/// rejected: a build that mangles its own version string must still be
/// reachable enough to be told to upgrade.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Version(Vec<u64>);

impl Version {
    pub fn parse(raw: &str) -> Option<Version> {
        let parts: Vec<u64> = raw
            .trim()
            .split('.')
            .map(|part| {
                let digits: String = part.chars().take_while(char::is_ascii_digit).collect();
                digits.parse::<u64>().ok()
            })
            .collect::<Option<Vec<u64>>>()?;
        if parts.is_empty() {
            return None;
        }
        Some(Version(parts))
    }
}

/// The three thresholds for one platform. All are optional and independent: a
/// platform with none configured is inert, which is how every environment
/// except production starts out.
#[derive(Debug, Clone, Default, Serialize)]
pub struct PlatformPolicy {
    /// Newest build published. Anything below it is at least `Optional`.
    pub latest: Option<String>,
    /// Below this, `Recommended`.
    pub min_recommended: Option<String>,
    /// Below this, `Required` - and refused by the API.
    pub min_supported: Option<String>,
}

impl PlatformPolicy {
    /// Severity for a client reporting `raw`.
    ///
    /// An unparseable or absent version is deliberately **not** treated as
    /// ancient. Locking out everything that fails to identify itself would turn
    /// a typo in a header name into a total outage, so an unknown client is
    /// left alone and simply told nothing.
    pub fn severity_for(&self, raw: &str) -> Severity {
        let Some(client) = Version::parse(raw) else {
            return Severity::None;
        };
        let below = |threshold: &Option<String>| {
            threshold
                .as_deref()
                .and_then(Version::parse)
                .is_some_and(|t| client < t)
        };

        if below(&self.min_supported) {
            Severity::Required
        } else if below(&self.min_recommended) {
            Severity::Recommended
        } else if below(&self.latest) {
            Severity::Optional
        } else {
            Severity::None
        }
    }
}

/// Every platform the server has an opinion about. Web is absent on purpose:
/// the browser loads whatever this server just served, so it cannot be out of
/// date in a way an update prompt would fix.
#[derive(Debug, Clone, Default)]
pub struct UpdatePolicy {
    pub android: PlatformPolicy,
    pub desktop: PlatformPolicy,
}

impl UpdatePolicy {
    pub fn for_platform(&self, platform: &str) -> Option<&PlatformPolicy> {
        match platform {
            "android" => Some(&self.android),
            "desktop" => Some(&self.desktop),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy() -> PlatformPolicy {
        PlatformPolicy {
            latest: Some("47".into()),
            min_recommended: Some("45".into()),
            min_supported: Some("40".into()),
        }
    }

    #[test]
    fn ladder_covers_each_band() {
        let p = policy();
        assert_eq!(p.severity_for("47"), Severity::None);
        assert_eq!(p.severity_for("48"), Severity::None); // ahead of the server
        assert_eq!(p.severity_for("46"), Severity::Optional);
        assert_eq!(p.severity_for("45"), Severity::Optional);
        assert_eq!(p.severity_for("44"), Severity::Recommended);
        assert_eq!(p.severity_for("40"), Severity::Recommended);
        assert_eq!(p.severity_for("39"), Severity::Required);
    }

    #[test]
    fn semver_compares_component_wise_not_lexically() {
        let p = PlatformPolicy {
            latest: Some("0.10.0".into()),
            min_recommended: None,
            min_supported: Some("0.2.0".into()),
        };
        // "0.9.0" > "0.10.0" as strings; as versions it is older.
        assert_eq!(p.severity_for("0.9.0"), Severity::Optional);
        assert_eq!(p.severity_for("0.1.9"), Severity::Required);
        assert_eq!(p.severity_for("0.10.0"), Severity::None);
    }

    #[test]
    fn unidentifiable_clients_are_never_locked_out() {
        let p = policy();
        assert_eq!(p.severity_for(""), Severity::None);
        assert_eq!(p.severity_for("nightly"), Severity::None);
    }

    #[test]
    fn unconfigured_platform_demands_nothing() {
        let p = PlatformPolicy::default();
        assert_eq!(p.severity_for("1"), Severity::None);
    }

    #[test]
    fn prerelease_suffix_does_not_reject_the_build() {
        let p = policy();
        assert_eq!(Version::parse("0.2.0-beta"), Version::parse("0.2.0"));
        assert_eq!(p.severity_for("39-rc1"), Severity::Required);
    }
}
