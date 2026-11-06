# Coffer P5: creazione volumi, sicurezza, preparazione al rilascio. Piano di implementazione

**Goal:** Coffer crea nuovi container VeraCrypt (FAT o exFAT) che **VeraCrypt su Windows monta e `chkdsk` trova puliti**, protegge l'app con blocco PIN/impronta e (opzionale) password ricordata con l'impronta, ed è pronta per un rilascio 1.0 (licenze, informazioni, export control, privacy, build riproducibile, metadati F-Droid/Play).

**Architecture:**
- `coffer-volume`: `create_volume` scrive header standard + backup (sali diversi, stessa master key), riempie di byte casuali le aree header nascoste (indistinguibili da un volume con nascosto), formattazione completa (zeri cifrati) o rapida.
- `coffer-fat`: `format` sopra il volume decifrato con `fatfs::format_volume`. `coffer-exfat`: `format` proprio (boot region principale + backup con checksum, FAT, bitmap, tabella up-case compressa, root con label).
- `coffer-ffi`: `create_fd_secret` con callback di avanzamento e annullamento.
- App: creazione guidata (`create/`), blocco app e impronta (`security/`), impostazioni, schermata Informazioni/Licenze.

**Tech Stack:** come P1–P4; nuove dipendenze: `rand_core`/`getrandom` (già nel grafo tramite le crate crittografiche, verificare), `androidx.biometric:biometric` (Apache-2.0).

**Spec:** `docs/design/2026-11-04-coffer-design.md` §5.2 (`Volume::create`, mkfs), §5.3, §6.1, §6.5, §6.6, §7, §9, §10 (M3).

## Global Constraints
- Vincoli P1–P4 invariati: clean-room (doc VeraCrypt + specifiche Microsoft FAT/exFAT), nessun `unwrap`/`expect`/`panic` nel codice di libreria, nessun dato in chiaro fuori dal volume, nessun permesso `INTERNET`, GPL-3.0-or-later, dipendenze controllate con `cargo-deny`.
- Valori dell'header non scritti nella documentazione (versione, versione minima, flag, settore) si copiano da un header del corpus VeraCrypt 1.26.29 **[oracolo]** e si annotano in `docs/security/format-notes.md`.
- Predefiniti (spec §6.5): cifrario AES, KDF PBKDF2-SHA-512, PIM 0, filesystem exFAT, formattazione **completa**; la rapida mostra un avviso. FAT avvisa del limite di 4 GB per file.
- RNG: `getrandom` del sistema; l'entropia tattile opzionale si **mescola** (SHA-512 su RNG‖tocchi), non sostituisce mai l'RNG.
- Password: mai in `UiState`, `Bundle`, log; `CharArray` azzerati come in P3/P4.
- "Ricorda password con impronta" **disattivato di default**, con avviso esplicito; chiave Keystore AES-GCM con `setUserAuthenticationRequired(true)`, `BIOMETRIC_STRONG`, invalidata da nuove impronte.
- FLAG_SECURE attivo di default, disattivabile nelle impostazioni.
- Dimensione minima del container: FAT 1 MiB, exFAT 4 MiB (validata nel core e nella UI); dimensione multipla di 512 byte.
- Deviazione dalla spec §5.3, registrata nel ledger: niente notifica permanente "Coffer aperto" per tutto il tempo in cui un volume è aperto (Android 14+ non ha un tipo di foreground service adatto a un'attesa indefinita). Il servizio resta attivo solo durante i trasferimenti (P4); il blocco automatico copre il resto.

## Review Focus
1. **Container creato e poi aperto da VeraCrypt su Windows** (ogni cifrario, PRF, PIM, FAT ed exFAT, dimensioni piccole e > 4 GiB per exFAT): monta, `chkdsk` pulito, file scritti da Coffer leggibili. Coperto dal Task 3 (script Windows).
2. **Annullamento o errore a metà creazione** (spazio SAF esaurito, annulla durante la formattazione completa): nessun container mezzo valido nell'elenco; il documento SAF viene cancellato. Test nel Task 5.
3. **Password di conferma diversa, password vuota senza keyfile, dimensione sotto il minimo o oltre lo spazio**: il pulsante "Crea" resta disattivato con il motivo visibile. Test nel Task 5.
4. **Impronte cambiate dopo "ricorda password"** (chiave invalidata) o biometria non disponibile: si torna alla password senza crash e il dato salvato viene cancellato. Test nel Task 6.
5. **App riaperta dai recenti con blocco app attivo**: il contenuto non è visibile prima dello sblocco (anche nella miniatura dei recenti). Test nel Task 6.

---

### Task 1: `create_volume` in `coffer-volume`

**Files:** Create `core/coffer-volume/src/create.rs`; Modify `core/coffer-volume/src/lib.rs`, `docs/security/format-notes.md`; Test `core/coffer-volume/tests/create.rs`

**Interfaces:**
- Produces: `pub struct CreateParams { pub size: u64, pub suite: CipherSuite, pub prf: Prf, pub quick: bool }`; `pub fn create_volume(dev: Arc<dyn BlockDevice>, creds: &Credentials, params: &CreateParams, rng: &mut dyn rand_core::CryptoRngCore, progress: &dyn Fn(u64, u64), cancel: &CancelToken) -> Result<OpenedVolume, CofferError>`; `pub const MIN_FAT: u64 = 1 << 20; pub const MIN_EXFAT: u64 = 4 << 20;`.
- Layout: header @0 e backup @size−131072 cifrati con lo stesso master key e **sali diversi**; aree @65536 e @size−65536 piene di byte casuali; `encrypted_area_start = 131072`, `encrypted_area_size = size − 262144`, `volume_size = encrypted_area_size`; gli altri campi dall'oracolo.
- Formattazione completa: scrive l'intera area dati come zeri cifrati (unità XTS corrette), a blocchi da 1 MiB, chiamando `progress(done,total)` e controllando `cancel` tra un blocco e l'altro (`Cancelled`). Rapida: non tocca l'area dati.

- [ ] **Step 1: test** `created_volume_opens_with_same_credentials` (ogni `CipherSuite::ALL` × 2 PRF a caso, MemDevice 1 MiB), `backup_header_opens_when_primary_is_destroyed` (azzera i primi 512 byte, `try_backup_headers=true`), `salts_of_header_and_backup_differ`, `hidden_header_area_is_not_zeros`, `full_format_data_reads_as_zeros_but_is_not_zero_on_disk`, `cancel_stops_full_format`, `too_small_is_rejected` (`size < MIN_FAT` → `InvalidPath`/nuova variante `TooSmall`), `size_not_multiple_of_512_is_rejected`.
- [ ] **Step 2:** leggere i campi dall'oracolo (header del corpus aperto con `decrypt_header`, stampare `Debug`), registrarli in `format-notes.md`.
- [ ] **Step 3:** implementare; **Step 4:** test verdi + clippy; **Step 5:** commit.

### Task 2: formattazione FAT ed exFAT

**Files:** Modify `core/coffer-fat/src/lib.rs` (+ `format.rs`), Create `core/coffer-exfat/src/format.rs`, `core/coffer-exfat/src/upcase.rs`; Modify `core/coffer-fs/src/lib.rs`; Test `core/coffer-fat/tests/format.rs`, `core/coffer-exfat/tests/format.rs`

**Interfaces:**
- Produces: `coffer_fat::format(dev: Arc<dyn BlockDevice>, label: &str) -> Result<(), CofferError>` (tipo FAT scelto da `fatfs` in base alla dimensione, come VeraCrypt); `coffer_exfat::format(dev: Arc<dyn BlockDevice>, label: &str) -> Result<(), CofferError>`; `coffer_fs::FsChoice { Fat, ExFat }` e `coffer_fs::format(vol: Arc<OpenedVolume>, fs: FsChoice, label: &str)`.
- exFAT: settori da 512; cluster 4 KiB fino a 256 MiB, 32 KiB fino a 32 GiB, 128 KiB oltre (valori consigliati Microsoft); FAT e heap allineati; boot region principale (settori 0–11) e backup (12–23) con checksum; bitmap e up-case nella heap marcate nel bitmap e nella FAT; root con voci Bitmap (0x81), Up-case (0x82, checksum della tabella) e Volume Label (0x83); `VolumeSerialNumber` casuale; `PercentInUse` corretto.
- Tabella up-case: generata da `char::to_uppercase` per i caratteri BMP con mappatura 1:1, compressa (sequenze identità come `0xFFFF, len`), con checksum §7.2.

- [ ] **Step 1: test** `format_then_write_read_roundtrip` (FAT 1/16/64 MiB, exFAT 4/64 MiB, con le API di scrittura di P4 e una riapertura), `exfat_boot_checksums_valid`, `exfat_upcase_maps_ascii_and_latin1`, `exfat_cluster_size_by_volume_size`, `name_hash_matches_new_upcase` (usa `coffer_exfat::name_hash`).
- [ ] **Step 2–4:** implementare, test verdi; **Step 5:** commit.

### Task 3: oracolo Windows per i volumi creati

**Files:** Create `core/coffer-fs/examples/create.rs`, `corpus/verify-created.ps1`

- `create <out.hc> <size> <cipher> <prf> <pim> <fat|exfat> <password>`: crea, formatta, scrive i file deterministici di `mutate.rs` (riusare la funzione spostandola in un modulo condiviso degli esempi) e il manifest atteso.
- `verify-created.ps1` (si eleva da solo, una richiesta UAC): monta ogni container con VeraCrypt (`/pim` quando ≠ 0), confronta albero e SHA-256 con il manifest, `chkdsk` in sola lettura, smonta; report in `corpus/out/created/verify-report.txt`.
- Matrice minima: AES/SHA-512 FAT 8 MiB; AES/SHA-512 exFAT 64 MiB; Serpent-Twofish-AES/Whirlpool exFAT; Camellia/BLAKE2s FAT con PIM 7; AES/Argon2id exFAT; AES/SHA-512 exFAT 5 GiB **rapida** (file > 4 GiB scritto).
- [ ] **Step 1:** esempio + script; **Step 2:** esecuzione → `RESULT: ALL OK`; **Step 3:** commit.

### Task 4: FFI di creazione

**Files:** Modify `core/coffer-ffi/src/lib.rs`; Test `core/coffer-ffi/tests/ffi.rs`

**Interfaces:**
- Produces: `#[uniffi::export(callback_interface)] trait FfiProgress { fn on_progress(&self, done: u64, total: u64); }`; record `FfiCreateOptions { size: u64, cipher: String, prf: String, pim: u32, fs: String /* "exFAT"|"FAT" */, quick: bool, label: String, extra_entropy: Vec<u8> }`; `create_fd_secret(fd: i32, secret_ptr: u64, secret_len: u64, options: FfiCreateOptions, progress: Box<dyn FfiProgress>, cancel: Arc<FfiCancel>) -> Result<Arc<FfiVault>, FfiError>` che porta il file a `size` (`set_len`), crea, formatta e restituisce il volume aperto in scrittura; `list_ciphers() -> Vec<String>`, `list_prfs() -> Vec<String>`.
- RNG: `OsRng` mescolato con `extra_entropy` tramite SHA-512 in un `ChaCha20Rng` con seme = SHA-512(OsRng 64 byte ‖ extra)[..32] (crate `rand_chacha`, licenza da verificare con `cargo-deny`).
- [ ] **Step 1: test** `create_then_reopen_via_fd` (file temporaneo, exFAT 4 MiB), `create_reports_progress_and_can_cancel`, `unknown_cipher_is_unsupported`; **Step 2–4:** implementare, binding rigenerati, test verdi; **Step 5:** commit.

### Task 5: creazione guidata Android

**Files:** Create `android/app/src/main/kotlin/app/coffer/create/CreateViewModel.kt`, `CreateScreen.kt`, `PasswordStrength.kt`; Modify `core/data/.../Native.kt`, `SessionManager.kt`, `VaultStore` (nessun cambio di schema), `platform/ContentResolverAccess.kt` (`deleteDocument(uri)`), `nav/CofferNavHost.kt`, `vaults/VaultsScreen.kt` (menu del FAB: "Aggiungi esistente" / "Crea nuovo"), stringhe IT/EN; Test `app/src/test/.../CreateViewModelTest.kt`, `core/data/.../SessionManagerTest.kt`

**Interfaces:**
- Consumes: Task 4 `create_fd_secret`.
- Produces: `NativeVaults.create(fd: Int, creds: NativeCredentials, options: CreateOptions, progress: (Long, Long) -> Unit, cancel: CancelSignal): VaultHandle`; `SessionManager.create(uri: String, label: String, req: CreateRequest, progress: (Long, Long) -> Unit, cancel: CancelSignal): VaultSession` (persiste il permesso, aggiunge allo `VaultStore`, apre la sessione; in caso di errore o annullamento cancella il documento e non aggiunge nulla).
- Passi della procedura (un'unica schermata a sezioni): posizione (`CreateDocument("application/octet-stream")`, nome predefinito `Coffer.hc`), dimensione (MB/GB, minimo per filesystem), cifratura (cifrario, KDF, PIM), password + conferma + indicatore di robustezza, keyfile opzionali, filesystem (exFAT predefinito; FAT con avviso 4 GB), formattazione completa/rapida (avviso), area "muovi il dito" opzionale per l'entropia, avanzamento con Annulla.
- `PasswordStrength.estimate(CharArray): Strength { WEAK, FAIR, GOOD, STRONG }` da lunghezza e classi di caratteri (stima di entropia in bit: <40, <60, <80, ≥80); avviso per < 20 caratteri come consiglia la documentazione VeraCrypt.
- [ ] **Step 1: test** `create_disabled_until_valid` (conferma diversa, password vuota senza keyfile, dimensione sotto il minimo), `create_success_adds_vault_and_navigates`, `create_failure_deletes_document_and_adds_nothing`, `cancel_deletes_document`, `passwords_wiped_after_create`, `strength_levels`.
- [ ] **Step 2–4:** implementare, test verdi; **Step 5:** commit.

### Task 6: sicurezza dell'app

**Files:** Create `android/app/src/main/kotlin/app/coffer/security/AppLock.kt`, `LockScreen.kt`, `BiometricVault.kt`, `settings/SettingsScreen.kt`; Modify `security/SecuritySettings.kt`, `MainActivity.kt`, `unlock/*`, `vaults/VaultsScreen.kt` (le impostazioni passano dal dialogo a una schermata), `gradle/libs.versions.toml` (`androidx.biometric`); Test `app/src/test/.../AppLockTest.kt`, `androidTest/.../SecureWindowTest.kt`

**Interfaces:**
- `SecuritySettings`: `secureWindow: Flow<Boolean>` (default true), `appLock: Flow<AppLockMode>` (`OFF`, `PIN`, `PIN_OR_BIOMETRIC`), PIN salvato come PBKDF2-SHA-256 (100 000 iterazioni, sale casuale 16 byte) in DataStore, mai in chiaro.
- `AppLock(clock, settings)`: `isLocked: StateFlow<Boolean>`; si blocca all'avvio e quando l'app torna in primo piano dopo il timeout di blocco automatico; 5 PIN errati → attesa crescente (30 s, raddoppia).
- `BiometricVault`: `remember(vaultId, password: CharArray, cipher: Cipher)`, `recall(vaultId, cipher): CharArray`, `forget(vaultId)`; chiave Keystore per vault (`coffer_pw_<id>`), AES-256-GCM, `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`; `KeyPermanentlyInvalidatedException` → `forget` + messaggio.
- Unlock: opzione "Ricorda con l'impronta" (spenta, con avviso) e pulsante impronta quando esiste un dato salvato; la password recuperata passa direttamente a `onUnlock` e viene azzerata.
- Volume chiuso male (spec §7): `FfiVolumeInfo.was_dirty` (exFAT `VolumeDirty` già impostato all'apertura) → dialogo "Il volume non è stato chiuso correttamente" con "Apri in sola lettura (consigliato)" / "Continua".
- Browser: spazio libero nella barra (spec §6.4) da `stats()`.
- [ ] **Step 1: test** `locks_on_start_and_after_timeout`, `pin_backoff_after_five_failures`, `pin_never_stored_in_clear`, `invalidated_key_forgets_and_falls_back`, `flag_secure_follows_setting` (strumentato), `dirty_volume_offers_read_only`.
- [ ] **Step 2–4:** implementare, test verdi; **Step 5:** commit.

### Task 7: preparazione al rilascio

**Files:** Create `scripts/third-party.ps1`, `legal/THIRD_PARTY.md` (generato), `legal/EXPORT.md`, `docs/privacy.md`, `fastlane/metadata/android/{en-US,it-IT}/{title,short_description,full_description}.txt` e `changelogs/1.txt`, `android/app/src/main/kotlin/app/coffer/about/AboutScreen.kt`, `scripts/check-reproducible.ps1`, `scripts/new-keystore.ps1`, `android/app/proguard-rules.pro`; Modify `android/app/build.gradle.kts`, `scripts/build-native.ps1`

- Informazioni: versione, licenza GPLv3, nota sui marchi (stringa `about_trademark` esistente), link testuali (nessuna rete), schermata licenze che mostra `THIRD_PARTY.md` (asset) con crate Rust e librerie Android.
- `EXPORT.md`: analisi UE 2021/821 nota generale sul software e EAR §742.15(b) (notifica per sorgente pubblico), con le azioni che restano all'utente.
- Build: `versionName "1.0.0"`, `versionCode 1`; firma release da `keystore.properties` non versionato (se manca, la release resta non firmata); `dependenciesInfo { includeInApk = false; includeInBundle = false }`; R8 attivo con regole per JNA e UniFFI; Rust con `--remap-path-prefix` e `-C link-arg=-Wl,--build-id=none`.
- `check-reproducible.ps1`: due build pulite della release non firmata, confronto degli hash degli APK → identici.
- [ ] **Step 1:** documenti e metadati; **Step 2:** About/Licenze; **Step 3:** build release minificata installata sull'emulatore: apertura, scrittura e creazione funzionano; **Step 4:** riproducibilità verificata; **Step 5:** commit.

### Task 8: verifica finale
- E2E sull'emulatore: crea exFAT e FAT dall'app (formattazione completa, con annullamento provato), importa file, blocca, estrai il container; Windows: `verify-created.ps1` sui container creati dal telefono → `RESULT: ALL OK`.
- Revisione finale indipendente, correzioni, ledger, memoria.

**Azioni che restano all'utente (non automatizzabili):** generare e custodire la chiave di firma (lo script `scripts/new-keystore.ps1` la crea in locale, fuori dal repo), registrazione Play Console e dichiarazione crittografia, eventuale notifica EAR, invio a F-Droid, pubblicazione su GitHub quando deciso.
