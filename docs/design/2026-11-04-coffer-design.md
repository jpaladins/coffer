# Coffer — Documento di design (v1.0)

- **Data:** 2026-11-04
- **Stato:** bozza da approvare
- **Prodotto:** Coffer, app Android open source compatibile con i volumi VeraCrypt®

---

## 1. Visione e obiettivi

Coffer porta su Android le funzioni essenziali di VeraCrypt (aprire, sfogliare, modificare e creare container cifrati) con un'esperienza pensata per il telefono, **senza root** e **senza accesso a internet**.

**Criteri di successo della v1.0**
1. Apre il 100% dei container del corpus di test creati con VeraCrypt desktop 1.26.30 (vedi §8).
2. I container creati o modificati da Coffer si aprono con VeraCrypt desktop e superano `fsck`.
3. Su un dispositivo di fascia media (ARMv8 con estensioni AES), un volume PBKDF2-SHA-512 con PIM predefinito si apre in meno di 3 secondi se l'utente indica la KDF, e in meno di 15 secondi con il rilevamento automatico di tutte le combinazioni (target da validare con i benchmark di M1).
4. Il dato in chiaro non viene mai scritto su memoria persistente.
5. L'app non dichiara il permesso `INTERNET`.

**Fuori obiettivo (v1.0):** cifratura di sistema o partizioni, NTFS, dischi USB OTG, creazione di volumi nascosti, integrazione con l'app File di sistema.

## 2. Contesto di mercato

Concorrenti compatibili con VeraCrypt: VaultPony (F-Droid, Apache-2.0), AndroidCrypt (F-Droid), Vault Explorer (Flutter, multi-formato), VC Port (port diretto, Apache-2.0 + TrueCrypt License 3.0), EDS.
**Differenziazione:** UX nativa Material 3 curata, prestazioni del core nativo Rust, nessun dato in chiaro su disco, nessun permesso di rete, licenza pulita, supporto ad Argon2id fin dalla v1.0.

## 3. Organizzazione

| Ruolo | Responsabilità | Cartella |
|---|---|---|
| Product | roadmap, priorità, store listing | `docs/product/` |
| Crypto & Security | core Rust, threat model, audit | `docs/security/` |
| Android | app, UX, rilasci | `docs/android/` |
| Design | brand, icona, design system | `docs/brand/` |
| QA | corpus di compatibilità e test | `docs/qa/` |
| Legal | licenze, marchi, clean-room | `legal/` |

Ogni decisione architetturale va registrata come ADR in `docs/adr/NNNN-titolo.md`. ADR iniziali da scrivere in M0:
- 0001 core in Rust;
- 0002 reimplementazione clean-room;
- 0003 GPLv3;
- 0004 nessun permesso INTERNET e niente pubblicità;
- 0005 minSdk 26.

## 4. Brand

- **Nome:** Coffer. Verifica preliminare (2026-11-04): nessuna app omonima sul Play Store, nessun marchio "Coffer" trovato per software. Omonimi vicini: estensione Firefox "Coffer" (TOTP), domanda USPTO "COFFERSY" (classe 42). Trattandosi di una parola del vocabolario il marchio denominativo è debole, quindi si valuta un **marchio figurativo** (nome + logo) presso l'EUIPO.
- **Da fare in M0:** ricerca ufficiale su EUIPO eSearch e USPTO TSDR, controllo dei domini (`coffer.app`, `getcoffer.app`, `coffer-app.org`), scelta del package name (`app.coffer` o `org.coffer.android`, secondo il dominio ottenuto).
- **Payoff:** "Your VeraCrypt® volumes, safe in your pocket", in italiano "I tuoi volumi VeraCrypt®, al sicuro in tasca".
- **Palette:** verde bosco `#0B3D2E` (primario), oro `#E8B04A` (accento), oro chiaro `#F5D38A` (luci). Temi chiaro e scuro, Material You opzionale.
- **Icona:** concept "Volume nel volume", cioè un esagono-cubo oro con un esagono interno che rappresenta il volume nascosto, su fondo verde bosco. Consegne: icona adattiva (primo piano e sfondo), versione monocromatica per le icone a tema, icona da 512 px per lo store, feature graphic 1024×500.
- **Tono di voce:** chiaro, rassicurante, orientato a cosa fare. Mai stringhe tecniche nude.

## 5. Architettura

### 5.1 Repository (monorepo)

```
coffer/
├─ core/                     Rust workspace (edition 2021+, MSRV fissata in rust-toolchain.toml)
│  ├─ coffer-crypto          cifrari, cascate, XTS, KDF, header (parse/serialize)
│  ├─ coffer-volume          BlockDevice, apertura e creazione volumi, volume nascosto
│  ├─ coffer-fat             FAT32 lettura e scrittura
│  ├─ coffer-exfat           exFAT lettura e scrittura
│  ├─ coffer-vfs             interfaccia comune ai filesystem (trait Filesystem)
│  └─ coffer-ffi             binding UniFFI verso Kotlin
├─ android/
│  ├─ app                    entry point, navigazione, DI (Hilt)
│  ├─ core/ui                design system Compose (tema Coffer)
│  ├─ core/data              repository dei container (URI SAF persistiti, DataStore)
│  ├─ core/native            wrapper Kotlin sopra coffer-ffi, librerie .so per abi
│  ├─ feature/vaults         elenco "I miei Coffer", apertura e chiusura
│  ├─ feature/browser        navigazione file, visualizzatori, import ed export
│  ├─ feature/create         creazione guidata dei volumi
│  └─ feature/settings       sicurezza, blocco automatico, licenze, informazioni
├─ corpus/                   script per generare i container di test (i binari non sono nel repo)
├─ docs/                     adr, security, brand, qa, product
└─ legal/                    LICENSE (GPLv3), NOTICE, TRADEMARKS.md, CLEAN_ROOM.md
```

ABI supportate: `arm64-v8a` (principale), `armeabi-v7a`, `x86_64` (emulatori). minSdk 26, targetSdk pari all'ultima API richiesta dal Play Store.

### 5.2 Unità e interfacce

- **`coffer-crypto`**
  - `trait BlockCipher` con le implementazioni AES, Serpent, Twofish, Camellia, Kuznyechik (crate RustCrypto) e le cascate VeraCrypt.
  - `Xts` a 512 byte per unità dati.
  - `Kdf`: PBKDF2 con SHA-512, SHA-256, Whirlpool, Streebog o BLAKE2s, più Argon2id, con calcolo delle iterazioni da PIM secondo la documentazione VeraCrypt.
  - `VolumeHeader::decrypt(bytes, creds) -> Result<Header>` e `Header::encrypt(...)`.
  - Le chiavi sono tenute in tipi con `Zeroize` e `ZeroizeOnDrop`.
- **`coffer-volume`**
  - `trait BlockDevice { read_at, write_at, len, flush }`, implementato da `FdDevice`, che usa il file descriptor ricevuto da Android tramite pread e pwrite.
  - `Volume::open(device, creds, options) -> Result<Volume>` prova in parallelo le combinazioni KDF × cifrario (rayon), legge prima l'header standard, poi quello nascosto, poi gli header di backup.
  - `Volume` espone un `BlockDevice` decifrato a livello di settore.
  - La protezione del volume nascosto blocca le scritture del volume esterno nell'area nascosta.
  - `Volume::create(device, params, rng)` crea un volume standard.
- **`coffer-vfs`**: `trait Filesystem { stat, list, open_read, open_write, create, mkdir, rename, remove, statfs, sync }`, con rilevamento automatico del tipo (FAT32, exFAT oppure NTFS riconosciuto ma non supportato).
- **`coffer-fat` / `coffer-exfat`**: implementazioni di `Filesystem`, con formattazione (`mkfs`) usata dalla creazione guidata. `coffer-fat` può partire dal crate `fatfs` (MIT), ma la licenza va verificata in M0 e registrata in un ADR. `coffer-exfat` è un'implementazione propria, dato che non esistono crate maturi con scrittura.
- **`coffer-ffi`**: API asincrona per Kotlin (UniFFI con supporto async). Handle opachi `VolumeHandle` e `FileHandle`; gli errori sono mappati su `CofferError`.

### 5.3 Flusso dei dati

1. **Aggiunta di un container:** `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`, poi `takePersistableUriPermission`. Viene salvato solo l'URI e un'etichetta (DataStore), mai password o chiavi.
2. **Apertura:** `ContentResolver.openFileDescriptor(uri, "rw")` (oppure `"r"` se il file non è scrivibile), poi `detachFd()` per passare il descrittore a Rust, quindi `Volume::open` e infine `Filesystem::detect`.
3. **Navigazione e lettura:** tramite chiamate `list` e `open_read`. I visualizzatori interni leggono in streaming; "Apri con" usa `StorageManager.openProxyFileDescriptor`, cioè un descrittore virtuale che legge da Rust a richiesta, quindi nessun file temporaneo.
4. **Scrittura:** l'import da SAF scrive in streaming a blocchi da 1 MiB nel `FileHandle`.
5. **Chiusura:** `sync`, poi rilascio degli handle, azzeramento delle chiavi e chiusura del descrittore. Viene chiusa anche la notifica di servizio.

Un volume aperto è tenuto vivo da un **foreground service** con una notifica "Coffer aperto: Chiudi tutto", così Android non termina il processo durante una copia lunga.

## 6. Funzionalità v1.0

1. **I miei Coffer:** elenco dei container, stato aperto/chiuso, aggiunta di un file esistente o creazione di uno nuovo, rinomina dell'etichetta, rimozione dall'elenco (il file non viene cancellato).
2. **Apertura:** password, PIM e keyfile (anche più di uno). Scelta opzionale di KDF e cifrario per un'apertura più rapida. Indicatore di avanzamento e possibilità di annullare.
3. **Navigazione:** cartelle, breadcrumb, ricerca nella cartella, ordinamento, vista a lista o griglia con miniature (generate solo in memoria), visualizzatori interni per immagini, testo, PDF, audio e video, "Apri con" in streaming, condivisione verso un'altra app tramite proxy file descriptor.
4. **Scrittura:** import di file e cartelle, nuova cartella, rinomina, sposta, elimina, esporta verso SAF. Spazio libero visibile.
5. **Creazione guidata:**
   - posizione (SAF) e dimensione;
   - cifrario (predefinito AES), KDF (predefinito PBKDF2-SHA-512, Argon2id disponibile) e PIM;
   - password con indicatore di robustezza, keyfile opzionali;
   - filesystem: exFAT predefinito, FAT32 opzionale (avviso sul limite di 4 GB per file);
   - formattazione rapida o completa, con avanzamento;
   - RNG di sistema (`getrandom`), più un mix opzionale di entropia tattile.
6. **Sicurezza:** blocco automatico (alla chiusura dell'app e/o dopo un timeout configurabile), chiusura di tutti i volumi, FLAG_SECURE (attivo di default, disattivabile), schermata di blocco dell'app con PIN o impronta opzionale. "Ricorda password con impronta" è disattivato di default: se attivato, la password viene cifrata con una chiave dell'Android Keystore che richiede l'autenticazione biometrica, e l'utente vede un avviso esplicito.

**Lingue:** italiano e inglese dalla v1.0, stringhe pronte per la traduzione (Weblate).

## 7. Errori e integrità

- **Errori del core:** `WrongCredentials`, `UnsupportedFilesystem(kind)`, `KdfOutOfMemory{required}`, `ReadOnlyDevice`, `HiddenVolumeProtectionTriggered`, `Corrupted(detail)`, `Io(detail)`, `NoSpace`, `Cancelled`. Ognuno ha un messaggio in linguaggio semplice con un'azione suggerita.
- **Argon2id:** prima di eseguirlo si confronta la memoria richiesta con `ActivityManager.MemoryInfo`. Se non basta, l'app mostra un errore chiaro invece di chiudersi.
- **Ordine delle scritture su FAT ed exFAT:** dati, poi la catena dei cluster (FAT o bitmap), poi la voce della cartella, poi `flush`. Per exFAT il flag `VolumeDirty` viene impostato in apertura in scrittura e azzerato alla chiusura pulita. Se all'apertura il flag risulta impostato, l'utente viene avvisato e l'app propone l'apertura in sola lettura.
- **Robustezza del parser:** nessun panic su input malformati (fuzzing, `#![deny(clippy::unwrap_used)]` nel codice di produzione).

## 8. Strategia di test

1. **Unit test** con i vettori ufficiali (NIST/IEEE per AES-XTS, vettori di riferimento per Serpent, Twofish, Camellia, Kuznyechik, PBKDF2, Argon2id) e i vettori pubblicati da VeraCrypt nella documentazione.
2. **Corpus di compatibilità:** script (`corpus/`) che, su una macchina con VeraCrypt 1.26.30 da riga di comando, generano container per ogni combinazione di cifrario e KDF, con PIM, keyfile, volume nascosto, FAT32 ed exFAT, e un file manifest con gli hash dei file contenuti. I test di integrazione in Rust aprono ogni container e verificano il manifest.
3. **Round-trip:** Coffer crea o modifica, poi in CI (Linux) VeraCrypt CLI monta il volume, `fsck.vfat -n` / `fsck.exfat -n` lo controllano e si verifica il manifest.
4. **Fuzzing:** `cargo-fuzz` su `VolumeHeader::decrypt`, sul parsing del FAT/boot sector e sulle voci di cartella exFAT.
5. **Android:** test unitari dei ViewModel; test UI Compose dei flussi apri → sfoglia → importa → chiudi; benchmark con Macrobenchmark su un dispositivo di fascia bassa.
6. **Sicurezza:** test che verifica l'assenza di file in chiaro in `cacheDir`/`filesDir` dopo i flussi principali; controllo del manifest in CI (nessun permesso `INTERNET`).

## 9. Legale e compliance

- **Licenza:** GPLv3-or-later per tutto il codice Coffer. Le dipendenze devono essere compatibili con GPLv3 (MIT, Apache-2.0, BSD); il controllo è automatico con `cargo-deny` e con il plugin di licenze Gradle.
- **Clean-room (`legal/CLEAN_ROOM.md`):** chi sviluppa il core non consulta il codice sorgente di VeraCrypt né di TrueCrypt. Fonti ammesse: documentazione pubblica di VeraCrypt (formato del volume, algoritmi, PIM), standard e paper degli algoritmi, vettori di test, comportamento osservato di VeraCrypt usato come scatola nera. Ogni fonte consultata viene annotata nel log del documento.
- **Marchi (`legal/TRADEMARKS.md`):** VeraCrypt® è un marchio di IDRIX. Si usa solo in forma descrittiva ("compatibile con i volumi VeraCrypt®"); niente loghi o grafica di VeraCrypt; nota "Coffer non è affiliato né approvato da IDRIX" nello store e nella schermata "Informazioni". Il nome TrueCrypt non viene usato.
- **NOTICE e attribuzioni:** elenco delle dipendenze con relative licenze, schermata "Licenze open source" nell'app.
- **Export control:** su Play Console va dichiarato l'uso di crittografia. Prima del primo rilascio va verificata l'applicabilità dell'esenzione per software pubblicamente disponibile (Reg. UE 2021/821, nota generale sul software; per gli USA, EAR §742.15(b), che prevede una notifica per il codice sorgente pubblico). L'esito va documentato in `legal/EXPORT.md`.
- **Privacy:** policy pubblicata su "nessuna raccolta, nessuna trasmissione di dati"; la Data Safety del Play Store dichiara che non viene raccolto alcun dato.
- **Monetizzazione:** nessuna pubblicità e nessun SDK di terze parti. Donazioni (GitHub Sponsors, Liberapay); valutazione post-1.0 di un'app "Coffer Supporter" a pagamento sul Play Store con lo stesso codice.

## 10. Rilascio e milestone

| Milestone | Contenuto | Criterio di uscita |
|---|---|---|
| M0 | Repo, CI (Rust + Android), documenti legali e brand, ADR 0001–0005, icona definitiva, verifiche marchio e dominio | CI verde su uno scheletro di app che si avvia |
| M1 | `coffer-crypto` e `coffer-volume` in lettura, FAT32 ed exFAT in lettura, app: elenco, apertura, navigazione, visualizzatori | Corpus completo apribile in lettura; beta interna |
| M2 | Scrittura FAT32 ed exFAT, import ed export, foreground service | Round-trip con VeraCrypt e fsck verdi |
| M3 | Creazione guidata, sicurezza (blocco, impronta), localizzazione IT/EN | **v1.0** in open testing sul Play Store e su F-Droid |

**Processo di rilascio:** semantic versioning, changelog pubblico, build riproducibili (requisito di F-Droid), chiave di firma generata e conservata offline con backup, Play App Signing.

**Roadmap dopo la 1.0:** creazione di volumi nascosti; cambio di password, PIM e keyfile; NTFS in sola lettura; DocumentsProvider (Coffer visibile nell'app File di sistema); dischi USB via OTG (libaums o simili, licenza da verificare); generazione di keyfile; widget per la chiusura rapida; Coffer Supporter.

## 11. Rischi principali

| Rischio | Mitigazione |
|---|---|
| exFAT in scrittura scritto da zero: possibili bug e corruzione dei dati | Fuzzing, round-trip con fsck, beta lunga, avviso "fai un backup" nella beta |
| Ambiguità nella documentazione del formato | Container reali usati come oracolo, test differenziali con VeraCrypt CLI |
| Memoria richiesta da Argon2id sui telefoni economici | Controllo preventivo della memoria e messaggio chiaro |
| Contaminazione di codice (TrueCrypt License) | Procedura clean-room documentata, revisione delle PR |
| Contestazione sul marchio "Coffer" | Ricerca ufficiale in M0, nome alternativo pronto (Celato) |
