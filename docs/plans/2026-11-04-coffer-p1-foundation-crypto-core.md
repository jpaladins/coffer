# Coffer P1: fondamenta e core crittografico in lettura. Piano di implementazione

**Goal:** monorepo Coffer avviato (legale, ADR, workspace Rust) e un core Rust che, dato un container creato da VeraCrypt e le credenziali giuste, decifra l'header ed espone i settori del volume in chiaro. Il tutto verificato contro un corpus di container reali.

**Architecture:** workspace Rust in `core/` con `coffer-crypto` (KDF, cifrari, cascate, XTS, header) e `coffer-volume` (layout del container, apertura, `BlockDevice` decifrato). Il corpus di container è generato con la riga di comando di VeraCrypt per Windows e fa da oracolo dei test di integrazione. L'app Android, il filesystem e la scrittura sono fuori da questo piano (P2–P5, vedi in fondo).

**Tech Stack:** Rust stable; crate RustCrypto (`aes`, `serpent`, `twofish`, `camellia`, `kuznyechik`, `xts-mode`, `sha2`, `whirlpool`, `streebog`, `blake2`, `hmac`, `pbkdf2`, `argon2`); `crc32fast`, `zeroize`, `rayon`, `thiserror`, `serde_json` (solo nei test); `cargo-deny`, `cargo-ndk`; PowerShell per gli script del corpus.

**Spec:** `docs/design/2026-11-04-coffer-design.md`

## Global Constraints

- Licenza del codice: GPL-3.0-or-later; ogni file sorgente inizia con `// SPDX-License-Identifier: GPL-3.0-or-later`.
- Clean-room: vietato aprire il codice sorgente di VeraCrypt o TrueCrypt. Fonti ammesse: documentazione su veracrypt.io, standard e paper degli algoritmi, container di test usati come scatola nera. Ogni pagina consultata va annotata in `legal/CLEAN_ROOM.md` (sezione "Log fonti").
- Dipendenze solo con licenza MIT, Apache-2.0, BSD-2/3, ISC, Zlib o Unicode-3.0 (applicato da `cargo-deny`).
- Codice di produzione senza `unwrap`/`expect`/`panic!`: `#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic)]` in ogni `lib.rs`.
- Tutte le chiavi e le password vanno in tipi `Zeroizing<…>` o `ZeroizeOnDrop`.
- Interi dell'header VeraCrypt in **big-endian**.
- Versione di riferimento del formato: VeraCrypt **1.26.30**.
- Identificativi in inglese, documentazione in italiano.

## Review Focus

1. **Password sbagliata:** dopo aver provato tutte le combinazioni deve restituire `CofferError::WrongCredentials`, mai un panic e mai un header "valido" per caso. Il magic e i due CRC vanno verificati tutti (test in Task 5).
2. **File troncato o troppo piccolo** (meno di 262 144 byte, cioè 2 × 131 072): deve restituire `CofferError::Corrupted`, senza panic e senza letture fuori dai limiti (test in Task 6).
3. **Header principale danneggiato:** l'apertura deve riuscire tramite l'header di backup in coda al file, e `OpenedVolume::used_backup_header` vale `true` (test in Task 6).
4. **Password non ASCII** (es. `pàsswörd€`): va codificata in UTF-8 come fa VeraCrypt 1.26 e deve aprire il container `utf8-pass` del corpus (test in Task 7).
5. **Annullamento durante la KDF:** `CancelToken::cancel()` deve far restituire `CofferError::Cancelled` entro 1 s anche durante PBKDF2 a 500 000 iterazioni (test in Task 5).

---

## Struttura dei file

```
.gitignore  .gitattributes  README.md
legal/LICENSE  legal/NOTICE  legal/CLEAN_ROOM.md  legal/TRADEMARKS.md
docs/adr/0001-core-rust.md … 0005-minsdk-26.md
docs/security/format-notes.md          note clean-room sul formato (fonti + valori)
docs/brand/brand.md  docs/brand/icon-concept.svg
corpus/generate.ps1                    genera container e manifest.json (fuori dal repo)
corpus/README.md
core/Cargo.toml  core/rust-toolchain.toml  core/deny.toml  core/.cargo/config.toml
core/coffer-crypto/src/{lib.rs, error.rs, prf.rs, kdf.rs, keyfile.rs, cipher.rs, xts.rs, header.rs, cancel.rs}
core/coffer-crypto/tests/{kdf_vectors.rs, xts_vectors.rs, header_roundtrip.rs}
core/coffer-volume/src/{lib.rs, device.rs, layout.rs, open.rs, decrypted.rs}
core/coffer-volume/tests/{open_synthetic.rs, corpus.rs}
scripts/check.ps1                      fmt + clippy + test + deny + build Android
```

---

### Task 0: Ambiente di sviluppo (azioni con conferma dell'utente)

**Files:** nessuno nel repo; risultati annotati in `docs/android/dev-setup.md` (creato nel Task 1).

- [ ] **Step 1:** installare rustup (profilo default, toolchain stable) da https://rustup.rs (download: chiedere conferma all'utente).
- [ ] **Step 2:** `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`, poi `cargo install cargo-ndk cargo-deny --locked`.
- [ ] **Step 3:** aggiornare VeraCrypt da 1.26.24 a 1.26.30 dal sito ufficiale veracrypt.io (download e installer: chiedere conferma all'utente; l'installer chiede i permessi di amministratore).
- [ ] **Step 4: verifica.** `rustc --version` restituisce una versione stable; `cargo ndk --version` funziona; `(Get-Item 'C:\Program Files\VeraCrypt\VeraCrypt.exe').VersionInfo.ProductVersion` restituisce `1.26.30`.

### Task 1: Scaffold del repository, legale, ADR, brand

**Files:**
- Create: tutti i file di primo livello elencati sopra in `legal/`, `docs/adr/`, `docs/brand/` e `docs/android/dev-setup.md`, più `README.md`, `.gitignore` (Rust `target/`, Gradle, `corpus/out/`, `*.hc`) e `.gitattributes` (`* text=auto eol=lf`, `*.ps1 eol=crlf`).
- Create: `core/Cargo.toml` (workspace con `coffer-crypto` e `coffer-volume`, `resolver = "2"`, `[workspace.package] license = "GPL-3.0-or-later"`, `edition = "2021"`), `core/rust-toolchain.toml` (canale `stable`, i tre target Android), `core/deny.toml` (licenze ammesse come da Global Constraints), `scripts/check.ps1`.

- [ ] **Step 1:** `git init`, branch `main`.
- [ ] **Step 2:** `legal/LICENSE` = testo integrale della GPLv3 (da https://www.gnu.org/licenses/gpl-3.0.txt). `legal/TRADEMARKS.md` e `legal/CLEAN_ROOM.md` con le regole della §9 della spec. `CLEAN_ROOM.md` contiene la tabella "Log fonti" (data, URL, cosa è stato preso).
- [ ] **Step 3:** ADR 0001–0005 in formato *Contesto / Decisione / Conseguenze*, ciascuno ricavato dalle §§3, 5 e 9 della spec.
- [ ] **Step 4:** `docs/brand/brand.md` (nome, payoff, palette `#0B3D2E` / `#E8B04A` / `#F5D38A`, tono di voce) e `docs/brand/icon-concept.svg` (esagono-cubo oro con esagono interno su fondo verde bosco, viewBox 108×108, zona sicura dell'icona adattiva di 66 dp centrata).
- [ ] **Step 5:** crate vuoti `coffer-crypto` e `coffer-volume` (con `lib.rs` contenente SPDX e i `deny` di clippy).
- [ ] **Step 6:** `scripts/check.ps1` esegue in ordine: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test`, `cargo deny check licenses`, `cargo ndk -t arm64-v8a build --release`. Si ferma al primo errore.
- [ ] **Step 7: verifica.** `pwsh scripts/check.ps1` termina con exit code 0.
- [ ] **Step 8:** commit `chore: scaffold monorepo, legal docs, ADRs, brand`.

### Task 2: Corpus di container reali (oracolo)

**Files:**
- Create: `corpus/generate.ps1`, `corpus/README.md`. L'output va in `corpus/out/` (ignorato da git): file `.hc` più `manifest.json`.

**Interfaces:**
- Produces: `corpus/out/manifest.json`, un array di oggetti `{ "file": "aes-sha512-fat.hc", "password": "…", "pim": 0, "keyfiles": ["kf1.bin"], "cipher": "AES", "prf": "SHA-512", "fs": "FAT", "size": 10485760, "kind": "standard" }`. Valori di `cipher`: i 15 nomi di `CipherSuite::name()` (Task 4). Valori di `prf`: `SHA-512`, `SHA-256`, `Whirlpool`, `BLAKE2s-256`, `Streebog`, `Argon2id`.

- [ ] **Step 1:** leggere su veracrypt.io la pagina "Command Line Usage" e annotarla nel log fonti. Prendere da lì la sintassi esatta di `VeraCrypt Format.exe /create … /size … /password … /pim … /encryption … /hash … /filesystem … /keyfile … /quick /silent /force` e i nomi esatti dei 15 algoritmi di cifratura e degli hash, Argon2id incluso.
- [ ] **Step 2:** `generate.ps1` crea:
  (a) 15 cifrari × SHA-512, FAT, 10 MiB, password `coffer-test`;
  (b) AES × gli altri 5 PRF (incluso Argon2id), FAT;
  (c) AES/SHA-512 con PIM 1 e PIM 485;
  (d) AES/SHA-512 con 2 keyfile generati da 4 KiB e 2 MiB e con keyfile senza password;
  (e) AES/SHA-512 exFAT 64 MiB;
  (f) `utf8-pass`: password `pàsswörd€`.
  Poi scrive `manifest.json`. Lo script richiede di essere eseguito come amministratore (l'utente approva l'UAC una volta).
- [ ] **Step 3:** il README documenta come creare a mano dalla GUI di VeraCrypt i due container con volume nascosto (`hidden-outer.hc`, password esterna `outer-pass`, password nascosta `hidden-pass`, AES/SHA-512) e come aggiungerli al manifest con `"kind": "hidden"`, perché la CLI di Windows non crea volumi nascosti.
- [ ] **Step 4: verifica.** Eseguire lo script: `manifest.json` contiene almeno 27 voci e ogni `.hc` ha la dimensione dichiarata.
- [ ] **Step 5:** commit `test: add VeraCrypt corpus generator`.

### Task 3: PRF, PBKDF2, PIM, keyfile, annullamento (`coffer-crypto`)

**Files:**
- Create: `core/coffer-crypto/src/{error.rs, prf.rs, kdf.rs, keyfile.rs, cancel.rs}`, `tests/kdf_vectors.rs`.

**Interfaces:**
- Produces:
  - `pub enum CofferError { WrongCredentials, Corrupted(String), Cancelled, KdfOutOfMemory { required_bytes: u64 }, Io(String), Unsupported(String) }` (thiserror).
  - `pub enum Prf { Sha512, Sha256, Whirlpool, Blake2s256, Streebog, Argon2id }` con `Prf::ALL: [Prf; 6]` e `fn name(&self) -> &'static str` (nomi come nel manifest).
  - `pub fn iterations(prf: Prf, pim: u32) -> u32`.
  - `pub fn derive_header_key(prf: Prf, password: &[u8], salt: &[u8; 64], pim: u32, cancel: &CancelToken) -> Result<Zeroizing<[u8; 192]>, CofferError>`.
  - `pub fn apply_keyfiles(password: &[u8], keyfiles: &[Vec<u8>]) -> Zeroizing<Vec<u8>>`.
  - `pub struct CancelToken` (Clone, basato su `Arc<AtomicBool>`) con `new()`, `cancel()`, `is_cancelled()`.

- [ ] **Step 1:** dalla documentazione VeraCrypt ("Header Key Derivation, Salt, and Iteration Count", "PIM", "Keyfiles", e la pagina su Argon2id della 1.26.29) annotare in `docs/security/format-notes.md`: lunghezza del salt (64), lunghezza della chiave derivata (192), iterazioni predefinite e formula PIM per volumi non di sistema, parametri Argon2id (memoria, iterazioni, parallelismo in funzione del PIM), algoritmo del pool dei keyfile (dimensione del pool, CRC32, limite di byte letti per file), lunghezza massima della password. Aggiornare il log fonti.
- [ ] **Step 2: test che falliscono** in `tests/kdf_vectors.rs`:
  - `iterations_default`: `iterations(Prf::Sha512, 0) == 500_000`;
  - `iterations_pim`: `iterations(Prf::Sha512, 1) == 16_000` e `iterations(Prf::Sha512, 485) == 500_000`. Se la formula annotata nello Step 1 differisce, prevale la documentazione: aggiornare questi valori e annotare la discrepanza.
  - `pbkdf2_sha512_matches_python` e `pbkdf2_sha256_matches_python`: password `b"password"`, salt di 64 byte `0x00..0x3f`, 1000 iterazioni, 192 byte di output, confrontati con l'esadecimale ottenuto da `python -c "import hashlib;print(hashlib.pbkdf2_hmac('sha512',b'password',bytes(range(64)),1000,192).hex())"` (e `'sha256'`). Il valore va incollato come costante nel test; la funzione interna `pbkdf2_raw(prf, pw, salt, iters, cancel)` viene esposta come `pub(crate)` e testata tramite `#[cfg(test)]` oppure esposta come `pub fn` con `#[doc(hidden)]`.
  - `cancel_stops_kdf`: un thread chiama `cancel()` dopo 100 ms; `derive_header_key(Prf::Sha512, …, pim 0)` restituisce `Err(Cancelled)` in meno di 1 s.
- [ ] **Step 3:** eseguire `cargo test -p coffer-crypto`. Esito atteso: FAIL (simboli non definiti).
- [ ] **Step 4:** implementare. PBKDF2 è un loop HMAC proprio per blocco, con controllo di `is_cancelled()` ogni 4096 iterazioni (non si può usare `pbkdf2::pbkdf2` in un'unica chiamata, perché non è annullabile). Argon2id usa il crate `argon2` con i parametri dello Step 1; prima di chiamarlo va calcolata la memoria richiesta e, se supera `max_kdf_memory` (parametro di configurazione, predefinito 1 GiB), si restituisce `KdfOutOfMemory`. La funzione `apply_keyfiles` segue l'algoritmo annotato.
- [ ] **Step 5:** `cargo test -p coffer-crypto` passa.
- [ ] **Step 6:** commit `feat(crypto): PRFs, PBKDF2 with PIM, Argon2id, keyfiles, cancellation`.

### Task 4: Cifrari, cascate, XTS (`coffer-crypto`)

**Files:**
- Create: `core/coffer-crypto/src/{cipher.rs, xts.rs}`, `tests/xts_vectors.rs`.

**Interfaces:**
- Produces:
  - `pub enum CipherSuite`, 15 varianti: `Aes, Serpent, Twofish, Camellia, Kuznyechik, AesTwofish, AesTwofishSerpent, CamelliaKuznyechik, CamelliaSerpent, KuznyechikAes, KuznyechikSerpentCamellia, KuznyechikTwofish, SerpentAes, SerpentTwofishAes, TwofishSerpent`. `CipherSuite::ALL`, `fn name(&self) -> &'static str` ("AES-Twofish-Serpent" ecc., uguali al manifest), `fn key_len(&self) -> usize` = 64 × numero di cifrari.
  - `pub struct XtsCascade` (ZeroizeOnDrop) con `fn new(suite: CipherSuite, key: &[u8]) -> Result<Self, CofferError>`, `fn decrypt_units(&self, data: &mut [u8], first_unit: u64)` e `fn encrypt_units(&self, data: &mut [u8], first_unit: u64)`, con unità da 512 byte e `data.len() % 512 == 0`.

- [ ] **Step 1:** annotare in `format-notes.md`, dalla documentazione VeraCrypt ("Encryption Algorithms", "Modes of Operation"), l'ordine di applicazione delle cascate (in "AES-Twofish-Serpent" il blocco viene cifrato prima con Serpent, poi con Twofish, poi con AES) e la disposizione delle chiavi (prima le chiavi primarie di tutti i cifrari in ordine di nome, poi le chiavi secondarie XTS). I test di integrazione del Task 7 sono l'oracolo finale: se falliscono solo sulle cascate, invertire l'ordine e annotarlo.
- [ ] **Step 2: test che falliscono**:
  - `ieee1619_vector1_aes128`: XTS-AES-128, chiave di 32 byte a zero, tweak (unità) 0, testo in chiaro di 32 byte a zero, testo cifrato `917cf69ebd68b2ec9b9fe9a3eadda692cd43d2f59598ed858c02c2652fbf922e`. Il test usa direttamente `xts-mode` con `aes::Aes128` e serve a validare il collegamento del crate.
  - `roundtrip_all_suites`: per ogni `CipherSuite::ALL`, con chiave casuale di `key_len()` byte, `encrypt_units` seguito da `decrypt_units` su 4096 byte con `first_unit = 256` restituisce l'originale, e il cifrato è diverso dal chiaro.
  - `unit_number_matters`: lo stesso blocco cifrato con `first_unit` 256 e 257 produce output diversi.
- [ ] **Step 3:** eseguire `cargo test -p coffer-crypto xts`. Esito atteso: FAIL.
- [ ] **Step 4:** implementare. Ogni cifrario è un `Xts128<C>` di `xts-mode` con chiavi di 256 bit; la cascata applica gli XTS in sequenza (per la decifratura in ordine inverso), con tweak = numero di unità in little-endian su 16 byte (`xts_mode::get_tweak_default`). Le unità vengono elaborate in parallelo con rayon quando sono più di 64.
- [ ] **Step 5:** `cargo test -p coffer-crypto` passa.
- [ ] **Step 6:** commit `feat(crypto): ciphers, cascades and XTS`.

### Task 5: Header del volume (`coffer-crypto`)

**Files:**
- Create: `core/coffer-crypto/src/header.rs`, `tests/header_roundtrip.rs`.

**Interfaces:**
- Consumes: `derive_header_key`, `iterations`, `XtsCascade`, `CipherSuite`, `Prf`, `CancelToken`.
- Produces:
  - `pub struct Credentials { pub password: Zeroizing<Vec<u8>>, pub pim: u32, pub keyfiles: Vec<Zeroizing<Vec<u8>>>, pub prf_hint: Option<Prf>, pub suite_hint: Option<CipherSuite> }`.
  - `pub struct VolumeHeader { pub version: u16, pub min_program_version: u16, pub hidden_volume_size: u64, pub volume_size: u64, pub encrypted_area_start: u64, pub encrypted_area_size: u64, pub flags: u32, pub sector_size: u32, pub master_key: Zeroizing<[u8; 256]>, pub suite: CipherSuite, pub prf: Prf }`.
  - `pub fn decrypt_header(raw: &[u8; 512], creds: &Credentials, cancel: &CancelToken) -> Result<VolumeHeader, CofferError>`.
  - `pub fn encrypt_header(h: &VolumeHeader, creds: &Credentials, salt: &[u8; 64]) -> Result<[u8; 512], CofferError>` (usata nei test, poi da P4).

- [ ] **Step 1:** annotare in `format-notes.md` (pagina "VeraCrypt Volume Format Specification") la tabella degli offset dell'header: salt 0–63; dall'offset 64 la parte cifrata: magic `"VERA"` @64, versione @68 (u16), versione minima @70 (u16), CRC32 dei byte 256–511 @72, dimensione del volume nascosto @92, dimensione del volume @100, inizio area cifrata @108, dimensione area cifrata @116, flag @124, dimensione del settore @128, CRC32 dei byte 64–251 @252, master key 256–511. Verificare ogni offset sulla documentazione: in caso di differenza prevale la documentazione.
- [ ] **Step 2: test che falliscono**:
  - `roundtrip_every_suite_sha512`: per ogni suite, `encrypt_header` seguito da `decrypt_header` restituisce un header con tutti i campi uguali, compresi `suite` e `prf` rilevati automaticamente. Usare `pim: 1` per non rallentare i test: 16 000 iterazioni con PIM 1.
  - `wrong_password_is_wrong_credentials`: restituisce `Err(WrongCredentials)`.
  - `crc_tamper_rejected`: un header cifrato valido decifrato con chiave giusta ma con un byte della master key alterato prima della cifratura (CRC di 72 non più coerente) viene rifiutato con `WrongCredentials`.
  - `hints_restrict_search`: con `prf_hint = Some(Sha256)` su un header SHA-512 restituisce `WrongCredentials`.
  - `cancel_during_search`: annullamento dopo 100 ms con PIM 0, risultato `Cancelled` in meno di 1 s.
- [ ] **Step 3:** eseguire `cargo test -p coffer-crypto header`. Esito atteso: FAIL.
- [ ] **Step 4:** implementare `decrypt_header`. Applicare `apply_keyfiles` alla password; per ogni PRF candidato (in parallelo con rayon) derivare la chiave una sola volta, poi per ogni suite candidata decifrare i byte 64–511 come unità 0 e controllare magic e due CRC. Al primo successo annullare gli altri thread tramite un `CancelToken` figlio. `master_key` contiene i primi `suite.key_len()` byte validi.
- [ ] **Step 5:** `cargo test -p coffer-crypto` passa.
- [ ] **Step 6:** commit `feat(crypto): volume header decrypt/encrypt with auto-detection`.

### Task 6: Layout e apertura del volume (`coffer-volume`)

**Files:**
- Create: `core/coffer-volume/src/{device.rs, layout.rs, open.rs, decrypted.rs}`, `tests/open_synthetic.rs`.

**Interfaces:**
- Consumes: `decrypt_header`, `encrypt_header`, `Credentials`, `VolumeHeader`, `XtsCascade`, `CancelToken`, `CofferError`.
- Produces:
  - `pub trait BlockDevice: Send + Sync { fn read_at(&self, off: u64, buf: &mut [u8]) -> Result<(), CofferError>; fn write_at(&self, off: u64, buf: &[u8]) -> Result<(), CofferError>; fn len(&self) -> u64; fn flush(&self) -> Result<(), CofferError>; }`.
  - `pub struct FileDevice` (`std::fs::File`, `read_at`/`write_at` posizionali con `FileExt`: `seek_read`/`seek_write` su Windows, `pread`/`pwrite` su unix) con `FileDevice::open(path, writable: bool)`. Su Android un `FromRawFd` lo costruirà P3.
  - `pub enum VolumeKind { Standard, Hidden }`.
  - `pub struct OpenedVolume { pub kind: VolumeKind, pub used_backup_header: bool, pub header: VolumeHeader, /* privato: device, cascade */ }`, che implementa `BlockDevice` con offset 0 = primo byte dell'area dati in chiaro e `len() = header.volume_size`.
  - `pub fn open_volume(dev: Arc<dyn BlockDevice>, creds: &Credentials, cancel: &CancelToken) -> Result<OpenedVolume, CofferError>`.
  - Costanti di layout in `layout.rs`: `HEADER_AREA = 65_536`, `STD_HEADER = 0`, `HIDDEN_HEADER = 65_536`, `DATA_START = 131_072`, backup standard a `len - 131_072`, backup nascosto a `len - 65_536`.

- [ ] **Step 1: test che falliscono** in `tests/open_synthetic.rs`, usando un `MemDevice` di test e container costruiti con `encrypt_header` più dati cifrati con `XtsCascade`:
  - `open_standard_reads_plain`: settore 0 dell'`OpenedVolume` uguale al testo in chiaro scritto (numero di unità = `(DATA_START + off) / 512`, da annotare in `format-notes.md` e confermare con il corpus nel Task 7);
  - `open_hidden_with_hidden_password`: `kind == Hidden`;
  - `backup_header_used_when_primary_zeroed`: header @0 azzerato, apertura riuscita con `used_backup_header == true`;
  - `too_small_is_corrupted`: dispositivo da 200 000 byte, risultato `Err(Corrupted(_))`;
  - `read_out_of_bounds_is_error`: `read_at(len - 256, &mut [0; 512])` restituisce `Err`, nessun panic;
  - `unaligned_read`: `read_at(100, &mut [0; 1000])` restituisce i byte giusti (lettura dei settori interessati e copia della porzione).
- [ ] **Step 2:** eseguire `cargo test -p coffer-volume`. Esito atteso: FAIL.
- [ ] **Step 3:** implementare. Ordine dei tentativi: header standard, poi nascosto, poi backup standard, poi backup nascosto. Gli errori `WrongCredentials` vengono accumulati e, solo se tutti i tentativi falliscono, si restituisce `WrongCredentials`. Per il volume nascosto l'area dati parte da `header.encrypted_area_start`.
- [ ] **Step 4:** `cargo test -p coffer-volume` passa.
- [ ] **Step 5:** commit `feat(volume): container layout, open with backup/hidden headers, decrypted block device`.

### Task 7: Integrazione con il corpus reale

**Files:**
- Create: `core/coffer-volume/tests/corpus.rs`.

**Interfaces:**
- Consumes: `open_volume`, `FileDevice`, `Credentials`, `manifest.json` (Task 2).

- [ ] **Step 1:** test `corpus_all_entries_open`. Legge `COFFER_CORPUS` (cartella contenente `manifest.json`). Se la variabile non è impostata il test **fallisce** con il messaggio "COFFER_CORPUS non impostata"; il test è marcato `#[ignore]`, così `cargo test` lo salta e `check.ps1` lo esegue con `-- --ignored`. Per ogni voce del manifest:
  1. apre il container con password, PIM e keyfile;
  2. verifica che `header.suite.name()` e `header.prf.name()` coincidano con il manifest e che `kind` sia corretto;
  3. legge il settore 0 del volume e verifica la firma `0x55 0xAA` ai byte 510–511, più `"FAT32   "` all'offset 82 se `fs == "FAT"` oppure `"EXFAT   "` all'offset 3 se `fs == "exFAT"`. Per i volumi FAT piccoli VeraCrypt potrebbe creare FAT16 (`"FAT16   "` all'offset 54): si accettano entrambi e si annota quale compare.
  Alla fine stampa una tabella dei risultati per voce e fallisce se anche una sola voce non passa.
- [ ] **Step 2:** test `corpus_wrong_password_rejected`: le prime 3 voci con password `"wrong"` restituiscono `WrongCredentials`.
- [ ] **Step 3:** eseguire `$env:COFFER_CORPUS="corpus/out"; cargo test -p coffer-volume --test corpus -- --ignored --nocapture`. In caso di errori su cascate, numero di unità o keyfile, correggere secondo le note dei Task 4 e 6 e documentare in `format-notes.md` quale ipotesi si è rivelata corretta.
- [ ] **Step 4: verifica.** Tutte le voci (almeno 27, più le due nascoste se create) passano.
- [ ] **Step 5:** aggiungere a `check.ps1` il passaggio corpus (eseguito se `COFFER_CORPUS` è impostata, altrimenti stampa un avviso **ben visibile**). Commit `test(volume): real VeraCrypt corpus integration`.

### Task 8: Build Android del core e benchmark di apertura

**Files:**
- Create: `core/coffer-volume/examples/bench_open.rs`, `docs/qa/benchmarks.md`.

- [ ] **Step 1:** `bench_open` prende come argomenti percorso, password e opzionalmente il PRF, apre il volume e stampa il tempo in millisecondi.
- [ ] **Step 2:** `cargo ndk -t x86_64 build --release --example bench_open`, avviare l'emulatore `Small_Phone`, `adb push` del binario e del container `aes-sha512-fat.hc`, quindi eseguirlo via `adb shell`.
- [ ] **Step 3: verifica.** Il binario apre il volume sull'emulatore. Registrare in `benchmarks.md` i tempi con PRF indicato e con rilevamento automatico. I target della spec (<3 s e <15 s) vanno validati su un dispositivo ARM reale, in P3.
- [ ] **Step 4:** `pwsh scripts/check.ps1` verde. Commit `chore: android cross-build and open benchmark`.

---

## Piani successivi (da scrivere al termine di P1)

- **P2: filesystem in lettura.** Crate `coffer-vfs`, `coffer-fat` e `coffer-exfat` in lettura; corpus esteso con file noti (manifest con gli hash, richiede di montare il volume con VeraCrypt come amministratore).
- **P3: app Android in lettura.** Gradle multi-modulo, UniFFI, SAF, elenco, apertura, navigazione, visualizzatori, proxy file descriptor, foreground service. Test su emulatore e benchmark su dispositivo reale. Beta interna (fine di M1).
- **P4: scrittura.** FAT32 ed exFAT in scrittura, import ed export, controlli round-trip con VeraCrypt e `fsck` (richiede WSL) (M2).
- **P5: creazione e sicurezza.** Creazione guidata, `mkfs`, blocco automatico, impronta, FLAG_SECURE, localizzazione, preparazione dello store e di F-Droid (M3, v1.0).
