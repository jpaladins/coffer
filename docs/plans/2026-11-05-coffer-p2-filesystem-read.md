# Coffer P2: lettura dei filesystem FAT ed exFAT. Piano di implementazione

**Goal:** dato un `OpenedVolume` (P1), elencare le cartelle, leggere metadati e contenuto dei file di volumi FAT12/16/32 ed exFAT creati da VeraCrypt, con verifica byte per byte contro file noti scritti da Windows.

**Architecture:**
- `coffer-vfs`: interfaccia comune, cioè il trait `Filesystem`, i tipi, il rilevamento del tipo di filesystem e la normalizzazione dei percorsi.
- `coffer-fat`: adattatore sopra il crate `fatfs` (MIT).
- `coffer-exfat`: implementazione propria in sola lettura dalla specifica pubblica Microsoft.
- `coffer-fs`: facciata con `mount()`.
- **Corpus:** viene esteso montando i container con VeraCrypt (montaggio senza privilegi di amministratore, verificato) e scrivendo un albero di file deterministico, con il manifest degli hash.

**Tech Stack:** Rust stable, `fatfs 0.3.6` (features `std`, `alloc`, `lfn`, `unicode`, senza `chrono`), `sha2` e `serde_json` (solo nei test), PowerShell + VeraCrypt CLI per il corpus.

**Spec:** `docs/design/2026-11-04-coffer-design.md` (§5.2 `coffer-vfs`/`coffer-fat`/`coffer-exfat`, §7, §8). Il P1 è completo (`docs/plans/2026-11-04-coffer-p1-foundation-crypto-core.md`).

## Global Constraints

- Tutti i vincoli globali del P1 restano validi: SPDX GPL-3.0-or-later; clean-room, cioè nessun codice di VeraCrypt o TrueCrypt (per FAT ed exFAT sono fonti ammesse la specifica Microsoft "FAT32 File System Specification" (fatgen103) e "exFAT file system specification" (2019), da annotare nel log fonti); niente `unwrap`/`expect`/`panic` nel codice di libreria; licenze delle dipendenze controllate da `cargo deny`.
- In P2 i filesystem si aprono **in sola lettura**: il dispositivo viene aperto read-only e nessuna funzione di questo piano scrive.
- I percorsi sono stringhe UTF-8 in stile POSIX (`/`, `/dir/file.txt`); il confronto dei nomi non distingue maiuscole e minuscole, come su FAT ed exFAT.
- Ogni input proveniente dal volume è ostile: catene di cluster cicliche, indici fuori intervallo, dimensioni incoerenti e cicli di directory producono `CofferError::Corrupted`, mai panic né loop infiniti.

## Review Focus

1. **Catena di cluster ciclica o fuori intervallo** (FAT ed exFAT): `read` e `list` restituiscono `Corrupted` in tempo limitato (test in Task 4 e 5).
2. **Nomi Unicode e lunghi** (`àèìòù €.txt`, nome da 120 caratteri, cartelle con spazi): elencati e risolti esattamente come li ha scritti Windows (test in Task 6).
3. **Lettura oltre la fine del file o a cavallo di confini di cluster**: `read` restituisce il numero di byte effettivamente letti (0 a EOF) e i byte giusti a qualsiasi offset (test in Task 4 e 5).
4. **File frammentato** (catena di cluster non contigua): il contenuto resta identico all'originale (test in Task 6, corpus `frag.bin`).
5. **exFAT con ValidDataLength < DataLength**: i byte oltre ValidDataLength valgono zero (test in Task 5).

---

## Struttura dei file

```
corpus/populate.ps1                  monta, scrive l'albero di file, scrive content-<file>.json, smonta
core/coffer-crypto/src/error.rs      + NotFound(String), NotADirectory(String), IsADirectory(String)
core/coffer-vfs/src/{lib.rs, types.rs, detect.rs, path.rs}
core/coffer-vfs/tests/{detect.rs, path.rs}
core/coffer-fat/src/{lib.rs, io.rs, fs.rs}
core/coffer-fat/tests/fat_synthetic.rs
core/coffer-exfat/src/{lib.rs, boot.rs, fat.rs, dir.rs, fs.rs, time.rs}
core/coffer-exfat/tests/{exfat_synthetic.rs, builder/mod.rs}
core/coffer-fs/src/lib.rs            mount()
core/coffer-fs/tests/corpus_content.rs
core/coffer-fs/examples/bench_read.rs
```

---

### Task 1: Corpus con contenuto noto

**Files:** Create `corpus/populate.ps1`; Modify `corpus/generate.ps1` (aggiungere `aes-sha512-fat-fat32.hc` da **300M**, che VeraCrypt formatta in FAT32, e `aes-sha512-fat12.hc` da **2M**); Modify `corpus/README.md`.

**Interfaces:**
- Produces: per ogni container popolato, `corpus/out/content-<nome>.json`, un array di `{ "path": "/dir/file", "dir": false, "size": 513, "sha256": "<hex>" }` (le directory hanno `size` 0 e `sha256` vuoto), radice esclusa. Popolati: `aes-sha512-fat12.hc`, `aes-sha512-fat.hc` (FAT16), `aes-sha512-fat-fat32.hc`, `aes-sha512-exfat.hc`, `aes-argon2-exfat.hc`.

- [ ] **Step 1:** `populate.ps1 -File <hc> -Password <pw> [-Hash <h>]`: monta con `VeraCrypt.exe /q /s /v <hc> /l Q /p <pw> /hash <h> /m ts /nowaitdlg`, scrive l'albero, calcola gli hash, smonta con `/q /s /u Q`. Albero deterministico (`System.Random(1234)`):
  - `empty.txt` (0 byte), `one.bin` (1), `sector.bin` (512), `sector-plus.bin` (513), `odd.bin` (4097);
  - `big.bin` (3 MiB; solo 256 KiB nel container FAT12);
  - `UPPER.TXT`, `lower.txt`, `àèìòù €.txt`, un file con nome di 120 caratteri `n…n.txt`;
  - `Cartella Con Spazi/sotto cartella/deep.txt`, la cartella vuota `vuota/`;
  - `many/f000.txt`…`f299.txt` (300 file da 10 byte ciascuno, per una directory su più cluster);
  - **frammentato**: scrivere `a.bin` (100 KiB) e `b.bin` (100 KiB), cancellare `a.bin`, poi scrivere `frag.bin` (300 KiB).
- [ ] **Step 2:** `generate.ps1` crea i due nuovi container; `populate.ps1` viene eseguito sui 5 container elencati.
- [ ] **Step 3: verifica.** Ogni `content-*.json` ha almeno 316 voci (FAT12: almeno 316, con `big.bin` ridotto) e `frag.bin` è presente.
- [ ] **Step 4:** commit `test: populate corpus containers with known file trees`.

### Task 2: `coffer-vfs`: tipi, percorsi, rilevamento

**Files:** Create i file `coffer-vfs/*` e `tests/{detect.rs,path.rs}`; Modify `coffer-crypto/src/error.rs` (nuove varianti) e `core/Cargo.toml` (membri del workspace).

**Interfaces:**
- Produces:
  - `pub enum FsKind { Fat12, Fat16, Fat32, ExFat, Ntfs, Unknown }`.
  - `pub struct DirEntry { pub name: String, pub is_dir: bool, pub size: u64, pub modified: Option<i64> /* secondi Unix UTC */ }`.
  - `pub struct FsStats { pub total_bytes: u64, pub free_bytes: Option<u64>, pub cluster_size: u32 }`.
  - `pub trait Filesystem: Send + Sync { fn kind(&self) -> FsKind; fn stat(&self, path: &str) -> Result<DirEntry, CofferError>; fn list(&self, path: &str) -> Result<Vec<DirEntry>, CofferError>; fn read(&self, path: &str, offset: u64, buf: &mut [u8]) -> Result<usize, CofferError>; fn stats(&self) -> Result<FsStats, CofferError>; }`.
  - `pub fn detect(boot_sector: &[u8; 512]) -> FsKind`.
  - `pub fn split_path(path: &str) -> Result<Vec<&str>, CofferError>` (rifiuta percorsi relativi, componenti `..`, `.` e caratteri NUL con `InvalidPath`; `"/"` → `[]`).
  - `pub fn names_equal(a: &str, b: &str) -> bool` (confronto senza distinzione di maiuscole, con `char::to_uppercase`).
- [ ] **Step 1: test che falliscono.** `detect`: boot sector sintetici FAT12, FAT16 e FAT32 (BPB con numero di cluster 4084, 4085, 65525 secondo la formula di fatgen103: 4084 → Fat12, 4085 → Fat16, 65525 → Fat32), `"EXFAT   "`@3 → ExFat, `"NTFS    "`@3 → Ntfs, tutti zero → Unknown, `BytsPerSec = 0` → Unknown (nessuna divisione per zero). `split_path`: `"/a/b"` → `["a","b"]`, `"/"` → `[]`, `"a"` → Err, `"/a/../b"` → Err, `"/a//b"` → `["a","b"]`. `names_equal("àÈ.TXT","Àè.txt")` → true.
- [ ] **Step 2:** `cargo test -p coffer-vfs` → FAIL. **Step 3:** implementare. **Step 4:** PASS. **Step 5:** commit `feat(vfs): filesystem trait, path rules, FAT/exFAT/NTFS detection`.

### Task 3: `coffer-fat`: adattatore `fatfs`

**Files:** Create i file `coffer-fat/*` e `tests/fat_synthetic.rs`.

**Interfaces:**
- Consumes: `BlockDevice` (coffer-volume), `Filesystem`, `DirEntry`, `FsKind`, `split_path`, `names_equal`.
- Produces: `pub struct FatFs`, `pub fn FatFs::open(dev: Arc<dyn BlockDevice>) -> Result<FatFs, CofferError>`, `impl Filesystem for FatFs`. Internamente `Mutex<fatfs::FileSystem<VolumeIo>>`, dove `VolumeIo { dev, pos }` implementa `Read + Write + Seek` sopra `BlockDevice` (in P2 `Write` restituisce un errore).
- [ ] **Step 1: test che falliscono** (immagini create nei test con `fatfs::format_volume` su `MemDevice`, da 1, 16 e 64 MiB per ottenere FAT12, FAT16 e FAT32, popolate con `fatfs` e poi riaperte con `FatFs::open`):
  - `kind_matches` (FAT12/16/32);
  - `list_root_and_subdir` (nomi lunghi Unicode, ordine indifferente);
  - `stat_file_and_dir`;
  - `read_whole_and_ranges` (file da 70 000 byte, letture a offset 0, 511, 4095, 69 999 e oltre l'EOF → 0 byte);
  - `not_found` e `read_on_dir_is_error` (`IsADirectory`);
  - `case_insensitive_lookup`;
  - `corrupted_boot_sector_is_corrupted` (BPB azzerato → `Corrupted`, nessun panic).
- [ ] **Step 2:** FAIL. **Step 3:** implementare (conversione di `fatfs::DateTime` in secondi Unix con calcolo manuale, senza chrono; per `read`: apertura del file, `seek`, `read` in loop fino a riempire il buffer o a EOF; ricerca di ogni componente scorrendo `iter()` con `names_equal`, perché fatfs fa già la ricerca senza distinzione di maiuscole ma non sui nomi Unicode non ASCII). **Step 4:** PASS. **Step 5:** commit `feat(fat): read-only FAT12/16/32 via fatfs`.

### Task 4: `coffer-exfat`: boot sector, FAT, catene di cluster

**Files:** Create `coffer-exfat/src/{lib.rs,boot.rs,fat.rs}` e `tests/builder/mod.rs` (costruttore di immagini exFAT usato solo nei test), `tests/exfat_synthetic.rs`.

**Interfaces:**
- Produces:
  - `pub struct BootInfo { pub bytes_per_sector: u32, pub sectors_per_cluster: u32, pub fat_offset: u32, pub fat_length: u32, pub cluster_heap_offset: u32, pub cluster_count: u32, pub root_cluster: u32, pub volume_length: u64 }`;
  - `pub fn parse_boot(region: &[u8]) -> Result<BootInfo, CofferError>` (`region` = i primi 12 settori; verifica firma, `MustBeZero` 11..64, shift entro i limiti della specifica (BytesPerSectorShift 9..=12, SectorsPerClusterShift ≤ 25 − BytesPerSectorShift), checksum del settore 11 calcolato sui settori 0–10 escludendo i byte 106, 107 e 112);
  - `pub fn cluster_chain(dev, boot, first: u32, contiguous: bool, len_bytes: u64) -> Result<Vec<u32>, CofferError>`, con al massimo `cluster_count` passi, cluster nell'intervallo `2..cluster_count+2` e `0xFFFFFFFF` come fine catena.
- [ ] **Step 1: test che falliscono:**
  - `boot_checksum_verified` (un bit cambiato → Corrupted);
  - `boot_fields_parsed`;
  - `chain_follows_fat`;
  - `chain_contiguous_ignores_fat`;
  - `chain_cycle_is_corrupted` (FAT[5] = 5) e `chain_out_of_range_is_corrupted`;
  - `chain_shorter_than_length_is_corrupted`.

  Il builder scrive boot region e checksum, FAT, bitmap, directory radice, file e cartelle, con flag NoFatChain configurabile e ValidDataLength configurabile.
- [ ] **Step 2:** FAIL. **Step 3:** implementare. **Step 4:** PASS. **Step 5:** commit `feat(exfat): boot region and cluster chains`.

### Task 5: `coffer-exfat`: directory, file, trait `Filesystem`

**Files:** Create `coffer-exfat/src/{dir.rs,fs.rs,time.rs}`; Modify `tests/exfat_synthetic.rs`.

**Interfaces:**
- Produces: `pub struct ExFatFs`, `pub fn ExFatFs::open(dev: Arc<dyn BlockDevice>) -> Result<ExFatFs, CofferError>`, `impl Filesystem for ExFatFs`. Dettagli:
  - insiemi di voci: File 0x85 (SecondaryCount, SetChecksum, attributi con 0x10 per le directory, timestamp di modifica @12 e incremento in decine di ms @21, offset UTC @23), poi Stream 0xC0 (flag @1 con bit1 NoFatChain, NameLength @3, ValidDataLength @8, FirstCluster @20, DataLength @24), poi Name 0xC1 (15 unità UTF-16 dal byte 2);
  - checksum dell'insieme: `c = (c >> 1 | c << 15) + byte`, saltando i byte 2 e 3 della prima voce; le voci con bit7 = 0 vengono saltate; 0x00 indica la fine della directory;
  - timestamp: secondi doppi nei bit 0–4, minuti 5–10, ore 11–15, giorno 16–20, mese 21–24, anno 25–31 + 1980; l'offset UTC, se il bit7 è attivo, è in passi di 15 minuti con segno.
- [ ] **Step 1: test che falliscono** (con il builder):
  - `list_root`, `nested_dirs`;
  - `long_name_three_name_entries` (nome di 40 caratteri);
  - `read_fat_chain_file` e `read_contiguous_file`;
  - `valid_data_length_zero_fill` (DataLength 8192, ValidDataLength 1000: i byte 1000..8192 sono a zero);
  - `bad_set_checksum_is_corrupted`;
  - `directory_cycle_is_corrupted` (una sottodirectory che punta al cluster della radice, con profondità massima 64 per `list` ricorsivo nei test e risoluzione dei percorsi limitata a 255 componenti);
  - `modified_time_converted` (2026-11-05 12:34:56 UTC → 1793882096).
- [ ] **Step 2:** FAIL. **Step 3:** implementare. **Step 4:** PASS. **Step 5:** commit `feat(exfat): directory entry sets, file reads, Filesystem impl`.

### Task 6: `coffer-fs`: `mount()` e integrazione con il corpus

**Files:** Create `coffer-fs/src/lib.rs`, `tests/corpus_content.rs`; aggiungere il passaggio a `scripts/check.ps1`.

**Interfaces:**
- Produces: `pub fn mount(volume: Arc<dyn BlockDevice>) -> Result<Box<dyn Filesystem>, CofferError>`, che legge il settore 0, chiama `detect` e restituisce FatFs o ExFatFs. Con Ntfs restituisce `Unsupported("NTFS")`, con Unknown `Corrupted`.
- [ ] **Step 1: test `corpus_content_matches` (`#[ignore]`, richiede `COFFER_CORPUS`).** Per ogni `content-*.json`: apre il container (le credenziali sono nel `manifest.json`), chiama `mount`, verifica che il tipo coincida con il filesystem dichiarato (fat12, FAT16 o FAT32 secondo il nome; exFAT), visita ricorsivamente da `/` e controlla che l'insieme dei percorsi sia uguale a quello del json. Per ogni file verifica dimensione e SHA-256 leggendo a blocchi da 64 KiB. Infine legge `frag.bin` a offset casuali (seed fisso) e confronta con la lettura intera.
- [ ] **Step 2:** FAIL (mount non definito). **Step 3:** implementare. **Step 4:** PASS su tutti e 5 i container. **Step 5:** commit `feat(fs): mount() facade, corpus content verification`.

### Task 7: Benchmark di lettura su Android

**Files:** Create `coffer-fs/examples/bench_read.rs`; Modify `docs/qa/benchmarks.md`.
- [ ] **Step 1:** `bench_read <container> <password> <path>` apre il container, chiama `mount`, legge il file a blocchi da 1 MiB e stampa i MB/s.
- [ ] **Step 2:** eseguirlo sull'emulatore con `big.bin` (exFAT e FAT32). **Verifica:** throughput registrato in `benchmarks.md`.
- [ ] **Step 3:** `scripts/check.ps1` verde con `COFFER_CORPUS`. Commit `chore: read benchmark`.
