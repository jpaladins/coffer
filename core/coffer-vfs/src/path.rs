// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_crypto::CofferError;

/// Splits an absolute path into its components. Empty components (`//`,
/// trailing `/`) are ignored; `.`, `..`, NUL and relative paths are rejected.
pub fn split_path(path: &str) -> Result<Vec<&str>, CofferError> {
    let bad = || CofferError::InvalidPath(path.to_string());
    if !path.starts_with('/') || path.contains('\0') {
        return Err(bad());
    }
    let parts: Vec<&str> = path.split('/').filter(|c| !c.is_empty()).collect();
    if parts.iter().any(|c| *c == "." || *c == "..") {
        return Err(bad());
    }
    Ok(parts)
}

/// Case-insensitive name comparison (FAT and exFAT names are case-preserving
/// but case-insensitive).
pub fn names_equal(a: &str, b: &str) -> bool {
    a.chars()
        .flat_map(char::to_uppercase)
        .eq(b.chars().flat_map(char::to_uppercase))
}
