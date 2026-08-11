
use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    None,
    Optional,
    Recommended,
    Required,
}

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

#[derive(Debug, Clone, Default, Serialize)]
pub struct PlatformPolicy {
    pub latest: Option<String>,
    pub min_recommended: Option<String>,
    pub min_supported: Option<String>,
}

impl PlatformPolicy {
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
        assert_eq!(p.severity_for("48"), Severity::None);
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
