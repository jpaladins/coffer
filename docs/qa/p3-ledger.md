# Registro di lavoro — piano: docs/plans/2026-11-05-coffer-p3-android-read.md
Pre-flight: T1 FFI surface consumed by T3 NativeVaults; T3 SessionManager consumed by T4-T7; T5 provider uses VaultSession.read — consistent.
Task 0: Ruling: modules :app, :core:native, :core:data only (spec lists feature modules) — fewer Gradle boilerplate for a read-only beta; split when write/create land — cost: later move of packages.
Task 1: Ruling: FfiError::Closed variant instead of Io{"closed"} — distinguishable in Kotlin — cost: none.
Task 1: Ruling: MPL-2.0 allowed in deny.toml (UniFFI) — GPLv3-compatible via MPL §3.3 secondary license; ADR 0003 updated — cost if wrong: replace UniFFI with hand-written JNI.
Task 1: Ruling: added open_path() export (tests/tools) beside open_fd — Windows host cannot use fds — cost: none.
Task 1: complete (commit ef71520, tests: cargo test -p coffer-ffi → 6/6; cargo-ndk 3 ABIs ok)
Task 2: Ruling: compileSdk 37 (targetSdk stays 36) — hilt-navigation-compose 1.4.0 and other AndroidX AARs require compiling against API 37 — cost: none at runtime.
Task 2: Ruling: FfiVault.close() renamed lock() — conflicts with AutoCloseable.close in generated Kotlin — cost: none.
Task 2: Ruling: manifest policy enforced by Gradle task verify<Variant>Manifest (wired to assemble/check) instead of a JVM ManifestTest — reads the real merged-manifest artifact — cost: none.
Task 2: Ruling: buildNative Exec calls PowerShell — Windows-only build host for now; CI on Linux needs a bash twin — cost: CI work later.
Task 2: complete (commit 8faa6d0, :app:assembleDebug + verifyDebugManifest ok; emulator logcat "native library loaded: true"; screenshot docs/qa/p3-screens/00-hello.png)
Task 3: Ruling: NativeVaults/CancelSignal/VaultHandle seam with own NativeCredentials (not FfiCredentials) — JVM tests cannot load the native lib via JNA — cost: one mapping layer.
Task 3: Ruling: VaultStore test uses in-memory DataStore — file DataStore cannot atomically replace its file on a Windows JVM; Android unaffected — cost: file I/O path untested on JVM (covered in T7 E2E).
Task 3: Ruling: SessionManager retries with KDF detection when a remembered lastPrf fails (stale after re-key) — test stale_remembered_prf_falls_back_to_detection — cost: one extra attempt on wrong password when a prf is remembered.
Task 3: complete (commit 4f0f635, tests: :core:data:testDebugUnitTest → 10/10)
Task 4: Ruling: tests and implementation for T4/T5 written in the same pass and run once (no observed RED for those 16 tests) — process deviation, tests assert spec behaviour — cost: a test could be vacuous; mitigated by E2E in T7.
Task 4: Ruling: UnlockViewModel/BrowserViewModel/ViewerViewModel use Hilt assisted injection (id passed to constructor) instead of SavedStateHandle — testable on the JVM — cost: none.
Task 4: Ruling: password field uses OutlinedSecureTextField with a non-saveable TextFieldState — never written to the saved-state Bundle (Review Focus 1); still a Java String-backed buffer until cleared — cost: password may linger in heap until GC.
Task 5: Ruling: ProxyReader + MimeTypes split out of Android classes for JVM tests; ErrnoException mapping only in the Android callback — cost: callback glue tested in T7 E2E.
Task 6: Ruling: debug-only intent extra e2e_screens lifts FLAG_SECURE for E2E screenshots; release always secure — cost: none for release.
Task 4: complete (commit 4e2b506, tests: :app:testDebugUnitTest VaultsAndUnlockViewModelTest 7/7)
Task 5: complete (commit 4e2b506, tests: BrowserViewerTest 9/9)
Task 6: complete (commit 4e2b506, tests: AutoLockTest 4/4 RED→GREEN)
Task 7: Ruling: E2E done by driving the real app + system picker via uiautomator/adb (docs/qa/p3-e2e.md) instead of an instrumented NativeE2ETest — exercises SAF, native, R8 release for real — cost: not automated in CI yet.
Task 7: found+fixed: password field allowed IME suggestions (KeyboardOptions overrode keyboardType) — now KeyboardType.Password, autocorrect off.
Task 7: open: accessibility dump exposes password text; FAB missing from accessibility tree; hidden/outer not indicated in UI.
Task 7: complete (commit 5371dfa, E2E 12 steps on emulator, release APK verified, unit tests green)
Revisione finale indipendente: 0 critical, 7 important, 13 minor.
Final: fixed lock-during-read crash — destroyed_native_object_maps_to_Closed RED→GREEN + device lock_during_reads_fails_cleanly
Final: fixed unlock racing auto-lock — unlock_finishing_after_closeAll_is_discarded RED→GREEN
Final: fixed secrets in unwiped UniFFI buffers — secret_blob_is_parsed_and_source_wiped / malformed_secret_blob_is_rejected_and_wiped RED→GREEN; emulator unlock re-verified
Final: fixed replayable share URIs — share_token_is_per_session_and_dies_with_it RED→GREEN + device old_share_uri_dies_with_its_session
Final: fixed viewer OOM — out_of_memory_while_loading_image_shows_too_large RED→GREEN (single exact-size array)
Final: fixed provider/export never run on device — ProviderAndLockTest 4/4 on emulator
Final: fixed a11y password exposure — verified on emulator: dump shows only bullets (no unit test possible on JVM)
Final: Ruling: proxy fd may serve already-fetched pages from the kernel FUSE cache after lock — data already delivered to the reader; unread regions fail — cost: a reader can re-read what it already read until it closes the fd.
Final: Ruling: OOM limit not derived from ActivityManager.memoryClass; catch + single buffer instead — cost: a 64 MiB image may still be rejected on small heaps (shows TooLarge, no crash).
Final: minor (deferred): non-atomic openIds publish — fixed incidentally (session map + openIds now under one lock)
Final: minor (deferred): rememberPrf failure — fixed incidentally (runCatching)
Final: minor (deferred): unlock succeeding after leaving the screen keeps the session registered
Final: minor (deferred): UTF-8 encoder may reallocate and leave an unwiped buffer for non-ASCII passwords
Final: minor (deferred): KDF retry after stale lastPrf doubles wrong-password cost
Final: minor (deferred): rapid folder taps can show out-of-order results (no load job cancellation)
Final: minor (deferred): 1 MiB text in one Text may jank
Final: minor (deferred): provider query returns name for unknown columns
Final: minor (deferred): manifest guard misses bundle<Variant> and READ_MEDIA_*
Final: minor (deferred): export/open-with with auto-lock Immediately locks the vault (picker backgrounds the app)
Final: minor (deferred): double tap on a vault card stacks two unlock screens
Final: minor (deferred): failed export leaves a partial plaintext file at the SAF destination
Final: minor (deferred): R8 keep rules broad
Final: commit e23cdef, check.ps1 with corpus ALL CHECKS PASSED, Android JVM 34/34, device 4/4, assembleRelease ok
