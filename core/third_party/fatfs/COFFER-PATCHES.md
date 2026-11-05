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
4. `src/dir.rs`, `Dir::rename_internal`: the destination entry is written
   before the source entry is freed (upstream frees first, so a failure while
   growing the destination directory loses the file). Found in review.
5. `src/table.rs` + `src/fs.rs`, `alloc_cluster`: a cluster that must be
   zeroed (directories) is zeroed before it is linked into the chain, so a
   failed zeroing (protected hidden volume) never leaves random bytes inside
   a directory. Found in review.
