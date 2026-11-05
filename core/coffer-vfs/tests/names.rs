// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_vfs::{split_parent, validate_name, CofferError};

#[test]
fn valid_names() {
    for n in [
        "a.txt",
        "àèìòù €.txt",
        "Cartella Con Spazi",
        ".hidden",
        &"n".repeat(255),
    ] {
        assert!(validate_name(n).is_ok(), "{n:?}");
    }
}

#[test]
fn invalid_names() {
    for n in [
        "",
        ".",
        "..",
        "a/b",
        "a\u{5c}b",
        "a:b",
        "a*b",
        "a?b",
        "a\"b",
        "a<b",
        "a>b",
        "a|b",
        "a\u{1}b",
        "trailing ",
        "trailing.",
        &"n".repeat(256),
    ] {
        assert!(
            matches!(validate_name(n), Err(CofferError::InvalidPath(_))),
            "{n:?} accepted"
        );
    }
}

#[test]
fn parent_and_name() {
    assert_eq!(
        split_parent("/a/b/c.txt").unwrap(),
        ("/a/b".to_string(), "c.txt")
    );
    assert_eq!(split_parent("/c.txt").unwrap(), ("/".to_string(), "c.txt"));
    assert!(split_parent("/").is_err());
}
