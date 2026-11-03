//! Finds the newest catalogue release on GitHub and mirrors it into a directory, the
//! layout `publish` uploads: manifest.json, the segments it lists (older chain parts live
//! in earlier releases), works.bin, extras.bin, people.bin and covers.bin.

use crate::images::USER_AGENT;
use crate::library::manifest_entries;
use sha2::{Digest, Sha256};
use std::cmp::Ordering;
use std::path::Path;
use std::time::Duration;
use tokio::io::AsyncWriteExt;

const RELEASES: &str = "https://api.github.com/repos/tn3w/Shelf/releases?per_page=100";
const SIDECARS: [&str; 4] = ["works.bin", "extras.bin", "people.bin", "covers.bin"];
const MAX_RELEASE: usize = 16;

pub struct Published {
    pub release: String,
    assets: Vec<(String, String)>,
}

/// Release labels look like `2026-10-31` or `2026-10-31-1`.
pub fn is_release(label: &str) -> bool {
    !label.is_empty()
        && label.len() <= MAX_RELEASE
        && label.split('-').all(|part| {
            !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit())
        })
}

/// Orders release labels like the app's `releaseOrder`.
pub fn compare_releases(left: &str, right: &str) -> Ordering {
    let parts = |label: &str| -> Vec<u64> {
        label.split(['-', '.']).map(|part| part.parse().unwrap_or(0)).collect()
    };
    parts(left).cmp(&parts(right))
}

pub fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(1800))
        .user_agent(USER_AGENT)
        .build()
        .map_err(|error| error.to_string())
}

fn published(release: &serde_json::Value) -> Option<Published> {
    let tag = release["tag_name"].as_str()?;
    let label = tag.strip_prefix("catalogue-").filter(|label| is_release(label))?;
    if release["draft"].as_bool() == Some(true) {
        return None;
    }
    let assets: Vec<(String, String)> = release["assets"]
        .as_array()?
        .iter()
        .filter_map(|asset| {
            let name = asset["name"].as_str()?.to_string();
            Some((name, asset["browser_download_url"].as_str()?.to_string()))
        })
        .collect();
    assets
        .iter()
        .any(|(name, _)| name == "manifest.json")
        .then(|| Published { release: label.to_string(), assets })
}

/// The newest published catalogue release with a manifest.
pub fn pick(releases: &serde_json::Value) -> Option<Published> {
    let found = releases.as_array()?.iter().filter_map(published);
    found.max_by(|a, b| compare_releases(&a.release, &b.release))
}

/// The newest release, or `None` when `current` is already it.
pub async fn newer(
    client: &reqwest::Client,
    current: Option<&str>,
) -> Result<Option<Published>, String> {
    let response = client.get(RELEASES).send().await.and_then(|r| r.error_for_status());
    let releases: serde_json::Value = response
        .map_err(|error| error.to_string())?
        .json()
        .await
        .map_err(|error| error.to_string())?;
    let published = pick(&releases).ok_or("no catalogue release with a manifest")?;
    let up_to_date = current.is_some_and(|current| {
        compare_releases(&published.release, current) != Ordering::Greater
    });
    Ok((!up_to_date).then_some(published))
}

async fn download(
    client: &reqwest::Client,
    url: &str,
    target: &Path,
    sha256: Option<&str>,
) -> Result<(), String> {
    let failed = |error: reqwest::Error| format!("{url}: {error}");
    let mut response = client
        .get(url)
        .send()
        .await
        .and_then(|r| r.error_for_status())
        .map_err(failed)?;
    let partial = target.with_extension("part");
    let mut file = tokio::fs::File::create(&partial).await.map_err(|e| e.to_string())?;
    let mut hash = Sha256::new();
    while let Some(chunk) = response.chunk().await.map_err(failed)? {
        hash.update(&chunk);
        file.write_all(&chunk).await.map_err(|error| error.to_string())?;
    }
    file.flush().await.map_err(|error| error.to_string())?;
    let digest: String =
        hash.finalize().iter().map(|byte| format!("{byte:02x}")).collect();
    if sha256.is_some_and(|expected| expected != digest) {
        return Err(format!("{url}: checksum mismatch"));
    }
    tokio::fs::rename(&partial, target).await.map_err(|error| error.to_string())
}

/// Fills `next` with `published`, linking segments `current` already holds.
pub async fn mirror(
    client: &reqwest::Client,
    published: &Published,
    current: &Path,
    next: &Path,
) -> Result<(), String> {
    let _ = tokio::fs::remove_dir_all(next).await;
    tokio::fs::create_dir_all(next).await.map_err(|error| error.to_string())?;
    let asset = |name: &str| {
        let found = published.assets.iter().find(|(known, _)| known == name);
        found.map(|(_, url)| url.clone())
    };
    let manifest_url = asset("manifest.json").ok_or("no manifest")?;
    download(client, &manifest_url, &next.join("manifest.json"), None).await?;
    let text =
        tokio::fs::read(next.join("manifest.json")).await.map_err(|e| e.to_string())?;
    let manifest: serde_json::Value =
        serde_json::from_slice(&text).map_err(|error| error.to_string())?;
    let entries = manifest["segments"].as_array().cloned().unwrap_or_default();
    for (entry, (_, _, id)) in entries.iter().zip(manifest_entries(&manifest)) {
        let name = format!("{id}.bin");
        let size = entry["size"].as_u64();
        let known = std::fs::metadata(current.join(&name)).ok().map(|meta| meta.len());
        if known.is_some() && known == size {
            std::fs::hard_link(current.join(&name), next.join(&name))
                .or_else(|_| {
                    std::fs::copy(current.join(&name), next.join(&name)).map(drop)
                })
                .map_err(|error| error.to_string())?;
            continue;
        }
        let url = entry["url"].as_str().ok_or("segment without url")?;
        download(client, url, &next.join(&name), entry["sha256"].as_str()).await?;
    }
    for name in SIDECARS {
        if let Some(url) = asset(name) {
            download(client, &url, &next.join(name), None).await?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn newest_catalogue_release_with_a_manifest_is_picked() {
        let asset = |name: &str| {
            serde_json::json!({
                "name": name,
                "browser_download_url": format!("https://x/{name}"),
            })
        };
        let releases = serde_json::json!([
            {"tag_name": "v1.5.0", "assets": [asset("manifest.json")]},
            {"tag_name": "catalogue-2026-12-05", "draft": true,
             "assets": [asset("manifest.json")]},
            {"tag_name": "catalogue-2026-11-05", "assets": [asset("covers.bin")]},
            {"tag_name": "catalogue-2026-10-31", "assets": [asset("manifest.json")]},
            {"tag_name": "catalogue-2026-10-30", "assets": [asset("manifest.json")]},
        ]);
        let picked = pick(&releases).unwrap();
        assert_eq!(picked.release, "2026-10-31");
        assert_eq!(picked.assets[0].1, "https://x/manifest.json");
    }

    #[test]
    fn releases_are_validated_and_ordered() {
        assert!(is_release("2026-10-31") && is_release("2026-10-31-1"));
        assert!(!is_release("") && !is_release("latest") && !is_release("2026--1"));
        assert_eq!(compare_releases("2026-10-31", "2026-10-31-1"), Ordering::Less);
        assert_eq!(compare_releases("2026-11-05", "2026-10-31-2"), Ordering::Greater);
    }
}
