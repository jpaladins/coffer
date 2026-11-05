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

/// Characters FAT and exFAT forbid in names (besides control characters).
const FORBIDDEN: &[char] = &['"', '*', '/', ':', '<', '>', '?', '\', '|'];

/// Checks a single name for creation on FAT/exFAT: 1..=255 UTF-16 units, no
/// forbidden or control characters, not `.`/`..`, no trailing space or dot
/// (Windows silently strips those, which would make the file unreachable).
pub fn validate_name(name: &str) -> Result<(), CofferError> {
    let bad = || CofferError::InvalidPath(name.to_string());
    let units = name.encode_utf16().count();
    if units == 0 || units > 255 || name == "." || name == ".." {
        return Err(bad());
    }
    if name.chars().any(|c| (c as u32) < 0x20 || FORBIDDEN.contains(&c)) {
        return Err(bad());
    }
    if name.ends_with(' ') || name.ends_with('.') {
        return Err(bad());
    }
    Ok(())
}

/// Splits an absolute path into its parent directory and last name.
pub fn split_parent(path: &str) -> Result<(String, &str), CofferError> {
    let parts = split_path(path)?;
    let (name, dirs) = parts
        .split_last()
        .ok_or_else(|| CofferError::InvalidPath(path.to_string()))?;
    Ok((format!("/{}", dirs.join("/")), name))
}
