# Coffer P3: app Android in sola lettura (prima beta). Piano di implementazione

**Goal:** un'app Android installabile che permette di aggiungere un container VeraCrypt dal selettore file di sistema, sbloccarlo (password, PIM, keyfile), sfogliarne le cartelle, visualizzare immagini e testo, esportare un file e aprirlo con un'altra app, il tutto senza root, senza permesso INTERNET e senza scrivere dati in chiaro su disco.

**Architecture:**
- Il core Rust (P1 e P2) viene esposto da un nuovo crate `coffer-ffi` tramite UniFFI 0.32 (binding Kotlin + JNA), compilato con cargo-ndk per `arm64-v8a`, `armeabi-v7a` e `x86_64` e impacchettato in `:core:native`.
- `:core:data` gestisce l'elenco dei container (DataStore) e le sessioni aperte.
- `:app` contiene l'interfaccia Compose (pacchetti `vaults`, `unlock`, `browser`, `viewer`), il tema Coffer, il `ContentProvider` per "Apri con" (proxy file descriptor) e la logica di blocco automatico.
- Architettura MVVM con `UiState` immutabile e flusso di effetti (`Channel`), DI con Hilt.

**Tech Stack:** AGP 9.5.0 (Kotlin integrato), Gradle 9.8.2, Kotlin 2.4.21 + plugin Compose, KSP 2.3.13, Compose BOM 2026.09.00, Hilt 2.60.1 + hilt-navigation-compose 1.4.0, Navigation 2.10.2, Lifecycle 2.11.0, Activity Compose 1.13.0, DataStore 1.3.0, kotlinx-coroutines 1.11.0, JNA 5.19.1 (aar), UniFFI 0.32.2; test JUnit4, coroutines-test, AndroidX Test 1.7.0, Compose UI test.

**Spec:** `docs/design/2026-11-04-coffer-design.md` (§5.3 flusso dati, §6 funzionalità 1–3 e 6, §7 errori, ADR 0004 e 0005).

## Global Constraints

- `minSdk = 26`, `targetSdk = compileSdk = 36`; package `app.coffer` (provvisorio, vedi spec §4); nome visualizzato "Coffer".
- Il manifest unito **non** contiene `android.permission.INTERNET`, contiene `android:allowBackup="false"` e nessun permesso di storage (si usa solo SAF). La verifica è automatica (Task 2).
- I byte in chiaro non vengono mai scritti in `filesDir`, `cacheDir` o nello storage esterno. Le miniature e i visualizzatori lavorano in memoria; "Apri con" usa `StorageManager.openProxyFileDescriptor`; l'esportazione scrive solo nella destinazione scelta dall'utente via SAF.
- Password e keyfile: `CharArray`/`ByteArray` azzerati dopo l'uso. Non vengono mai salvati in `UiState` (che sopravvive alla configurazione) né nel `SavedStateHandle`.
- Tutte le chiamate native avvengono su `Dispatchers.IO` iniettato; gli errori Rust sono mappati su `CofferError` lato Kotlin e poi su messaggi IT/EN (tono della spec §4).
- `FLAG_SECURE` è attivo di default su tutte le finestre.
- Licenza GPL-3.0-or-later; intestazione SPDX nei file Kotlin; nessuna dipendenza con licenza incompatibile (JNA è Apache-2.0/LGPL-2.1: si usa sotto Apache-2.0).

## Review Focus

1. **Rotazione dello schermo o processo terminato durante lo sblocco:** la password non sopravvive nello stato; lo sblocco in corso viene annullato in modo pulito; nessun crash (test in Task 4).
2. **Container su un provider SAF che revoca il permesso o file spostato:** l'apertura restituisce un messaggio chiaro, non un crash (test in Task 3).
3. **File grande aperto in un visualizzatore:** immagini oltre i 64 MiB o testo oltre 1 MiB vengono rifiutati o troncati con un avviso, senza OutOfMemory (test in Task 5).
4. **App in background:** dopo il timeout del blocco automatico tutte le sessioni vengono chiuse e la UI torna all'elenco; i file descriptor proxy aperti da altre app vengono invalidati (test in Task 6).
5. **Password errata e annullamento:** messaggio comprensibile; il pulsante Annulla interrompe entro 1 s (test in Task 4 ed E2E in Task 7).

---

### Task 1: crate `coffer-ffi` (UniFFI)

**Files:** Create `core/coffer-ffi/{Cargo.toml, src/lib.rs, tests/ffi.rs, uniffi-bindgen.rs}`; Modify `core/Cargo.toml` (membro del workspace).

**Interfaces (Rust → Kotlin):**
- `#[derive(uniffi::Error)] enum FfiError { WrongCredentials, Cancelled, OutOfMemory { required_bytes: u64 }, Corrupted { detail: String }, Unsupported { detail: String }, NotFound { path: String }, NotADirectory { path: String }, IsADirectory { path: String }, InvalidPath { path: String }, Io { detail: String } }` (conversione da `CofferError`).
- `#[derive(uniffi::Record)] struct FfiEntry { name: String, is_dir: bool, size: u64, modified: Option<i64> }`, `struct FfiStats { total_bytes: u64, free_bytes: Option<u64>, fs_kind: String }`, `struct FfiVolumeInfo { cipher: String, prf: String, hidden: bool, used_backup_header: bool, fs_kind: String, size: u64 }`.
- `#[derive(uniffi::Object)] struct FfiCancel` con `new()` e `cancel()`.
- `#[derive(uniffi::Record)] struct FfiCredentials { password: Vec<u8>, pim: u32, keyfiles: Vec<Vec<u8>>, prf: Option<String>, max_kdf_memory: u64, try_backup_headers: bool }`.
- `#[uniffi::export] fn open_fd(fd: i32, writable: bool, creds: FfiCredentials, cancel: Arc<FfiCancel>) -> Result<Arc<FfiVault>, FfiError>`: prende possesso del file descriptor (`File::from_raw_fd` su unix; su Windows non disponibile, per i test esiste `open_path(path, …)`).
- `#[derive(uniffi::Object)] struct FfiVault` con `info()`, `list(path)`, `stat(path)`, `read(path, offset, len: u32) -> Vec<u8>` (len ≤ 4 MiB, altrimenti `InvalidPath`), `stats()`, `close()` (le chiamate successive restituiscono `Io{"closed"}`).
- Le credenziali sono convertite in `Zeroizing` e azzerate.
- [ ] **Step 1: test che falliscono** (`tests/ffi.rs`, sul `MemDevice` del P1 tramite la funzione di test `open_device`): `wrong_password_maps_to_wrong_credentials`; `list_and_read_roundtrip` (immagine FAT creata con fatfs in un container sintetico P1 con PIM 1); `read_len_cap`; `closed_vault_errors`; `cancel_maps_to_cancelled`. Esecuzione: `cargo test -p coffer-ffi` → FAIL.
- [ ] **Step 2:** implementazione con le macro procedurali di UniFFI (`uniffi::setup_scaffolding!()`), `crate-type = ["cdylib", "lib"]`. **Step 3:** PASS. **Step 4:** `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 build --release -p coffer-ffi` riesce. **Step 5:** commit `feat(ffi): UniFFI surface for opening and reading volumes`.

### Task 2: progetto Gradle, modulo nativo, avvio su emulatore

**Files:** Create `android/{settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew*, gradle/wrapper/*}`, `android/core/native/{build.gradle.kts, src/main/AndroidManifest.xml}`, `android/app/{build.gradle.kts, src/main/AndroidManifest.xml, src/main/kotlin/app/coffer/{CofferApp.kt, MainActivity.kt}}`, `android/app/src/main/res/{values/strings.xml, values-it/strings.xml, mipmap-anydpi/ic_launcher.xml, drawable/ic_launcher_foreground.xml, drawable/ic_launcher_monochrome.xml, values/colors.xml}`, `scripts/build-native.ps1`, `android/app/src/test/kotlin/app/coffer/ManifestTest.kt`.

- [ ] **Step 1:** wrapper Gradle 9.8.2, generato con una distribuzione Gradle scaricata nello scratchpad.
- [ ] **Step 2:** `scripts/build-native.ps1` esegue cargo-ndk per i 3 ABI con output in `android/core/native/src/main/jniLibs`, poi genera i binding Kotlin con `uniffi-bindgen generate --library <libcoffer_ffi host> --language kotlin --out-dir android/core/native/src/main/kotlin`. I file generati vanno in `.gitignore` e vengono rigenerati dal task Gradle `buildNative` (Exec), di cui `preBuild` dipende.
- [ ] **Step 3:** icona adattiva dal concept `docs/brand/icon-concept.svg` (vector drawable: esagoni su `#0B3D2E`) più la versione monocromatica.
- [ ] **Step 4:** `ManifestTest` (test JVM sul manifest unito `build/intermediates/merged_manifest/debug/…`, prodotto da `processDebugManifest`) verifica: nessun permesso `INTERNET` né `*STORAGE*`, `allowBackup="false"`. In più, un task `verifyManifest` collegato a `check`.
- [ ] **Step 5: verifica.** `./gradlew :app:assembleDebug :app:testDebugUnitTest`; installazione sull'emulatore; l'activity mostra "Coffer"; `adb logcat` senza errori di caricamento della libreria nativa (`System.loadLibrary` riuscito chiamando `FfiCancel()`).
- [ ] **Step 6:** commit `feat(android): Gradle project, native module, launcher, manifest guard`.

### Task 3: `:core:data`, container salvati e sessioni

**Files:** Create `android/core/data/{build.gradle.kts, src/main/kotlin/app/coffer/data/{VaultRef.kt, VaultStore.kt, VaultSession.kt, SessionManager.kt, CofferException.kt, NativeVaults.kt}, src/test/kotlin/...}`.

**Interfaces:**
- `data class VaultRef(val id: String, val label: String, val uri: String, val lastPrf: String?)`.
- `interface VaultStore { val vaults: Flow<List<VaultRef>>; suspend fun add(uri: String, label: String): VaultRef; suspend fun remove(id: String); suspend fun rememberPrf(id: String, prf: String) }`, implementato con DataStore Preferences e JSON (kotlinx.serialization).
- `sealed class CofferException`, che mappa ogni `FfiError` più `PermissionLost` (SAF revocato o `FileNotFoundException`).
- `interface NativeVaults { fun open(fd: Int, creds: UnlockRequest, cancel: FfiCancel): VaultSession }`, un seam per i test.
- `class SessionManager(store, native, contentResolver, io: CoroutineDispatcher)` con `suspend fun unlock(ref, req): Result<VaultSession>` (apre il PFD "r", `detachFd`, passa a Rust, salva `lastPrf`), `fun session(id): VaultSession?`, `fun closeAll()`, `val openIds: StateFlow<Set<String>>`.
- `UnlockRequest(password: CharArray, pim: Int, keyfileUris: List<String>, prf: String?)`: la password diventa byte UTF-8, che vengono azzerati.
- [ ] **Step 1: test che falliscono** (JVM, `NativeVaults` finto, `ContentResolver` astratto dietro `FdOpener`): `unlock_success_registers_session_and_remembers_prf`, `permission_lost_maps_to_PermissionLost`, `wrong_password_keeps_no_session`, `password_bytes_zeroed_after_unlock`, `closeAll_closes_every_session`, `store_add_remove_roundtrip` (DataStore in una cartella temporanea).
- [ ] **Step 2:** FAIL. **Step 3:** implementazione. **Step 4:** PASS. **Step 5:** commit.

### Task 4: tema, elenco dei container, sblocco

**Files:** `app/src/main/kotlin/app/coffer/{ui/theme/*, vaults/*, unlock/*, nav/CofferNavHost.kt}`, test in `app/src/test`.

- **Vaults:** `VaultsUiState(vaults, openIds)`; azioni: aggiungi (`OpenDocument` con `takePersistableUriPermission`, etichetta = nome visualizzato del file), rimuovi, apri. Stato vuoto con spiegazione e pulsante.
- **Unlock:** `UnlockUiState(pim: String, showPim, keyfiles: List<KeyfileUi>, prf: String?, phase: Idle|Unlocking|Error(msg))`. La password è tenuta solo nel composable con `rememberSaveable(saver = non-saver)`, cioè `remember` semplice: si perde alla rotazione per scelta. Il pulsante Annulla chiama `FfiCancel.cancel()`. Se esiste `lastPrf` viene usato come suggerimento al primo tentativo, con rilevamento automatico come ripiego. Il primo tentativo non usa gli header di backup; dopo `WrongCredentials` compare l'azione "Prova header di backup".
- Effetto `NavigateToBrowser(id)`.
- [ ] **Step 1: test** (VM con `SessionManager` finto e `StandardTestDispatcher`): `unlock_success_emits_navigate`, `wrong_password_shows_error_and_offers_backup`, `cancel_returns_to_idle_within_1s`, `ui_state_never_contains_password` (nessun campo di tipo String o CharArray con il valore della password: verifica tramite `toString()` dello stato), `last_prf_used_as_hint_then_fallback`. **Step 2:** FAIL, **Step 3:** implementazione, **Step 4:** PASS e test UI Compose dell'elenco vuoto o con 2 elementi. **Step 5:** commit.

### Task 5: navigazione, visualizzatori, esportazione, "Apri con"

**Files:** `app/.../browser/*`, `app/.../viewer/*`, `app/.../provider/VaultFileProvider.kt` (manifest: provider non esportato con `grantUriPermissions`), test.

- **Browser:** `BrowserUiState(vaultId, path, entries, loading, error)`; ordinamento con le cartelle prima e poi per nome (senza distinzione di maiuscole); breadcrumb; "indietro" risale di un livello; menu "Esporta…" e "Apri con…".
- **Viewer:** immagini decodificate da `ByteArray` in memoria con `BitmapFactory` e `inSampleSize` calcolato sulla dimensione dello schermo (limite: 64 MiB letti); testo UTF-8 fino a 1 MiB con avviso di troncamento; altri tipi → "Apri con".
- **Export:** `CreateDocument` → copia in streaming a blocchi da 1 MiB su `Dispatchers.IO`, con avanzamento e annullamento.
- **VaultFileProvider:** URI `content://app.coffer.files/<vaultId>/<pathEncoded>`. `openFile` restituisce `StorageManager.openProxyFileDescriptor(MODE_READ_ONLY, callback, handler)` con un callback che legge da `VaultSession.read`; `getType` dal nome del file; `query` restituisce DISPLAY_NAME e SIZE. Se la sessione è chiusa, `onRead` lancia `ErrnoException(EIO)`. L'intent `ACTION_VIEW` porta `FLAG_GRANT_READ_URI_PERMISSION`.
- [ ] **Step 1: test:** `entries_sorted_dirs_first_case_insensitive`, `navigate_up_from_root_finishes`, `image_over_limit_rejected`, `text_truncated_with_flag`, `export_copies_exact_bytes` (sink in memoria), `provider_read_after_close_fails` (unit test del callback). **Step 2:** FAIL, **Step 3:** implementazione, **Step 4:** PASS. **Step 5:** commit.

### Task 6: sicurezza dell'app

**Files:** `app/.../security/{AutoLock.kt, SecureWindow.kt}`, `app/.../settings/*` (solo il timeout del blocco), test.
- `FLAG_SECURE` sull'activity. `AutoLock` osserva `ProcessLifecycleOwner`: con l'app in `ON_STOP` avvia un timer (default 30 s, impostabile tra Subito, 30 s, 2 min e 5 min, salvato in DataStore); alla scadenza chiama `SessionManager.closeAll()` e la navigazione torna all'elenco. Al ritorno in `ON_START` prima della scadenza il timer viene annullato.
- [ ] **Step 1: test:** `lock_after_timeout_closes_all` (tempo virtuale), `return_before_timeout_keeps_sessions`, `immediate_option_closes_on_stop`. **Step 2–4.** **Step 5:** commit.

### Task 7: E2E su emulatore e beta

- [ ] **Step 1:** test instrumentato `NativeE2ETest`: copia `aes-sha512-exfat.hc` e `aes-sha512-fat-fat32.hc` (via `adb push` in `/data/local/tmp` e lettura dal test con `ParcelFileDescriptor.open`), li sblocca tramite `SessionManager` + native vero, elenca `/` e confronta SHA-256 di `/big.bin` e `/àèìòù €.txt` con i valori di `content-*.json` (passati come argomenti dell'instrumentation).
- [ ] **Step 2:** percorso reale guidato via adb (`uiautomator dump` + `input tap`): container in `/sdcard/Download` → Aggiungi → selettore di sistema → Sblocca (password sbagliata, poi giusta) → sfoglia `Cartella Con Spazi/sotto cartella` → apri `deep.txt` → torna indietro → app in background oltre il timeout → elenco. Screenshot (`screencap`) di ogni passo in `docs/qa/p3-screens/`.
- [ ] **Step 3:** `./gradlew :app:assembleRelease` con R8 (keep rules per JNA/UniFFI) e firma debug; verifica che l'APK release apra un volume. **Verifica:** `apkanalyzer manifest permissions` non elenca INTERNET.
- [ ] **Step 4:** benchmark di sblocco e lettura nell'app (log `Coffer/Perf`) annotati in `docs/qa/benchmarks.md`. Commit.

## Rimandato (P4 e P5)
Scrittura, creazione dei volumi, impronta digitale, foreground service per operazioni lunghe, DocumentsProvider, moduli feature separati (`feature:vaults`, `feature:browser`) quando la UI crescerà, localizzazione completa.
