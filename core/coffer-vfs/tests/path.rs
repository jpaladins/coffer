// SPDX-License-Identifier: GPL-3.0-or-later
use coffer_vfs::{names_equal, split_path, CofferError};

#[test]
fn split_valid_paths() {
    assert_eq!(split_path("/").unwrap(), Vec::<&str>::new());
    assert_eq!(split_path("/a/b").unwrap(), vec!["a", "b"]);
    assert_eq!(split_path("/a//b/").unwrap(), vec!["a", "b"]);
    assert_eq!(split_path("/àèìòù €.txt").unwrap(), vec!["àèìòù €.txt"]);
}

#[test]
fn reject_invalid_paths() {
    for p in ["", "a", "a/b", "/a/../b", "/./a", "/a\0b"] {
        assert!(
            matches!(split_path(p), Err(CofferError::InvalidPath(_))),
            "{p:?} accepted"
        );
    }
}

#[test]
fn names_compare_case_insensitively() {
    assert!(names_equal("àÈ.TXT", "Àè.txt"));
    assert!(names_equal("UPPER.TXT", "upper.txt"));
    assert!(!names_equal("a.txt", "b.txt"));
    assert!(!names_equal("a", "aa"));
}
