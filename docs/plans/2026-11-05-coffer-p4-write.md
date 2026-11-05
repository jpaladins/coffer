# Coffer P4: scrittura nei volumi. Piano di implementazione

**Goal:** Coffer modifica i file dentro i volumi FAT12/16/32 ed exFAT (importa, crea cartelle, rinomina, sposta, cancella). Il risultato deve essere **valido per Windows**: dopo le modifiche, il container montato con VeraCrypt viene riletto da Windows con hash identici e `chkdsk` non segnala errori. È inclusa la protezione del volume nascosto quando si scrive in quello esterno.

**Architecture:**
- `coffer-volume`: aggiunge `flush`, la protezione dell'area nascosta e l'apertura in scrittura.
- `coffer-vfs`: estende il trait con le operazioni di scrittura.
- `coffer-fat`: usa la scrittura di `fatfs`, con orari reali e una cache dei settori aggiornata a ogni scrittura (write-through).
- `coffer-exfat`: implementazione propria della scrittura, dalla specifica Microsoft. Comprende bitmap di allocazione, catene FAT/NoFatChain, insiemi di voci con NameHash (tabella up-case), crescita delle directory, flag VolumeDirty.
- `coffer-ffi`: API di scrittura in streaming.
- **App:** apertura in lettura/scrittura via SAF, interfaccia per importare, creare cartelle, rinominare e cancellare, foreground service per le operazioni lunghe.

**Tech Stack:** come P1–P3. Verifica con VeraCrypt CLI + Windows + `chkdsk` (elevato, una richiesta UAC per ogni giro di verifica).

**Spec:** `docs/design/2026-11-04-coffer-design.md` §6.4, §7 (ordine delle scritture, VolumeDirty), §8.3 (round-trip), M2.

## Global Constraints
- Vincoli di P1–P3 invariati: clean-room (specifiche Microsoft FAT ed exFAT), nessun `unwrap` nel codice di libreria, nessun dato in chiaro su disco al di fuori del volume cifrato.
- **Ordine delle scritture (spec §7):** dati, poi allocazione (FAT/bitmap), poi voce di directory, poi flush. Una sessione exFAT imposta `VolumeDirty` alla prima scrittura e lo azzera al `sync`/chiusura pulita.
- **Volume nascosto:** se aperto con protezione, qualunque scrittura che tocca l'area nascosta restituisce `HiddenVolumeProtected`; la scrittura non avviene, nemmeno in parte.
- Nomi: si rifiutano i caratteri non validi per FAT/exFAT (`"*/:<>?\|` e i caratteri di controllo); lunghezza massima 255 unità UTF-16.

## Review Focus
1. **Spazio esaurito durante un import:** errore `NoSpace`; il file parziale viene rimosso e le strutture restano coerenti per `chkdsk`.
2. **Rinomina o spostamento su un nome esistente o dentro sé stessa** (cartella dentro una sua sottocartella): errore, nessuna corruzione.
3. **Directory exFAT che deve crescere oltre un cluster** (300 file): catena estesa correttamente e `DataLength` della directory padre aggiornata.
4. **Interruzione a metà scrittura** (app terminata): al riavvio il volume si apre; exFAT segnala VolumeDirty; Windows lo monta.
5. **Scrittura nel volume esterno con un volume nascosto presente:** con la protezione attiva i dati nascosti restano intatti byte per byte.

---

### Task 1: scrittura a livello di volume e protezione del volume nascosto
- `OpenOptions { try_backup_headers, protect_hidden: Option<Credentials> }`: con la protezione attiva viene aperto anche l'header nascosto e calcolato l'intervallo `[start, start+size)` dell'area nascosta in coordinate del volume esterno; `OpenedVolume::write_at` rifiuta le scritture che lo intersecano con `CofferError::HiddenVolumeProtected` (variante nuova).
- Test sintetici: scrittura fuori dall'area permessa; scrittura che tocca l'area rifiutata senza modificare alcun byte; password nascosta sbagliata → `WrongCredentials`.

### Task 2: trait di scrittura in `coffer-vfs`
- `Filesystem` aggiunge: `create_file(path)`, `write(path, offset, data) -> usize`, `set_len(path, len)`, `mkdir(path)`, `remove(path)` (file o directory vuota), `rename(from, to)`, `sync()`. Implementazione di default: `Unsupported("read-only")`.
- Validazione dei nomi `validate_name(name) -> Result<(), InvalidPath>` con test.

### Task 3: scrittura FAT (`fatfs`)
- `VolumeIo::write` scrive sul dispositivo e aggiorna la cache dei settori (write-through); `TimeProvider` con l'orario di sistema in ora locale.
- Le operazioni usano le API di `fatfs` (`create_file`, `create_dir`, `remove`, `rename`); la dimensione si estende con `seek` + `write` e si tronca con `truncate`.
- Test su immagini sintetiche FAT12/16/32: ogni operazione seguita da riapertura e rilettura; spazio esaurito → `NoSpace` e file parziale rimosso; rinomina su un nome esistente → errore.

### Task 4: scrittura exFAT
- Bitmap di allocazione (lettura e modifica), allocatore che preferisce cluster contigui (NoFatChain) e passa a una catena FAT quando non è possibile estendere in modo contiguo (conversione della catena).
- Tabella up-case: lettura in formato compresso e `NameHash` (specifica §7.6.4); insieme di voci con checksum.
- Crescita delle directory, aggiornamento di `DataLength`/`ValidDataLength` e dei timestamp con offset UTC.
- Cancellazione con bit InUse azzerato e cluster liberati; rinomina e spostamento come nuovo insieme di voci più cancellazione del vecchio.
- VolumeDirty in `VolumeFlags` (byte 106, escluso dal checksum di boot).
- Test con il builder sintetico (esteso con bitmap e up-case reali) per ogni operazione e i casi della Review Focus 1–3.

### Task 5: verifica round-trip con Windows
- `core/coffer-fs/examples/mutate.rs`: applica a una copia di un container uno script deterministico di operazioni (import di file noti, cartelle annidate, 300 file, rinomina, spostamento, cancellazione, file da 5 MiB, sovrascrittura parziale) e scrive il manifest atteso.
- `corpus/verify-writes.ps1` (si eleva da solo): monta ogni container modificato con VeraCrypt, confronta albero e SHA-256 visti da Windows con il manifest, esegue `chkdsk X:` (in sola lettura) e richiede "nessun problema trovato", poi smonta. Container: FAT12, FAT16, FAT32, exFAT e il volume esterno con protezione del nascosto (verifica che il nascosto sia intatto).

### Task 6: FFI di scrittura
- `FfiVault`: `mkdir`, `remove`, `rename`, `create_file`, `write(path, offset, bytes)`, `set_len`, `sync`, `is_writable`; `open_fd_secret(writable=true)`; opzione `protect_hidden` con un secondo blocco di segreti.
- Test Rust sul container sintetico.

### Task 7: app Android
- Sblocco in lettura/scrittura quando il permesso SAF di scrittura è disponibile, altrimenti sola lettura con badge. Opzione "Proteggi volume nascosto" nello sblocco (seconda password).
- Browser: FAB "Importa" (selezione multipla), "Nuova cartella", menu "Rinomina", "Sposta in…" e "Elimina" (con conferma); avanzamento e annullamento; foreground service con notifica per import ed export lunghi; `sync` alla chiusura.
- Test dei ViewModel e test strumentato: import → riapertura → hash corretti.

### Task 8: E2E e revisione
- Import di file reali sull'emulatore, poi il container modificato viene riportato sul PC e verificato con `verify-writes.ps1`.
