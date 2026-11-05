# Local patches to fatfs 0.3.6 (MIT, see LICENSE.txt)

Vendored from crates.io `fatfs` 0.3.6 and used through `[patch.crates-io]`.

1. `src/dir.rs`, `Dir::write_entry`: no LFN entries for `.` and `..`. Upstream
   writes long-name entries before them, so they are no longer the first two
   entries of a new directory and Windows `chkdsk` reports
   "unrecoverable error in folder". Found by `corpus/verify-writes.ps1`.
2. `src/dir.rs`, `Dir::create_dir`: ".." of a directory created in the root is
   0 (upstream writes the root cluster on FAT32). `Dir::dotdot_cluster`.
3. `src/dir.rs`, `Dir::rename_internal`: a moved directory's ".." is updated
   to its new parent. Both found by `corpus/verify-writes.ps1` ("errors in .
   and/or ..").
