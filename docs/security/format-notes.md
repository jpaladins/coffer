# Note sul formato VeraCrypt (clean-room)

Raccolte **solo** dalla documentazione pubblica (`C:\Program Files\VeraCrypt\docs\html\en`, versione 1.26.29) e dal comportamento osservato. Le voci marcate **[oracolo]** non sono specificate in modo univoco dalla documentazione e sono state risolte con i test sul corpus (`corpus/`).

## Derivazione della chiave dell'header (pagina "Header Key Derivation")
- Salt: 64 byte (512 bit), in chiaro all'inizio dell'header.
- KDF: PBKDF2-HMAC con SHA-512, SHA-256, BLAKE2s-256, Whirlpool o Streebog; oppure Argon2id.
- Output: 192 byte. Ogni cifrario usa il prefisso necessario (64 byte per cifrario: 32 di chiave primaria e 32 di chiave secondaria XTS).
- Iterazioni PBKDF2 (volumi non di sistema e file container): PIM = 0 → 500 000; PIM > 0 → 15 000 + PIM × 1000.
- Argon2id: parallelismo 1. Con PIM > 0: m_cost = min(64 + (PIM − 1) × 32, 1024) MiB; t_cost = 3 + ⌊(PIM − 1) / 3⌋ se PIM ≤ 31, altrimenti 13 + (PIM − 31). Con PIM = 0 si usano i parametri di PIM = 12 (416 MiB, 6 iterazioni). Vanno richiesti sempre 192 byte, perché la lunghezza dell'output entra nel calcolo di Argon2.
- Argon2id **[oracolo, confermato]**: versione 0x13, nessun secret né associated data; salt = i 64 byte del salt dell'header.

## Cascate (pagina "Header Key Derivation")
- Nella cascata "AES-Twofish-Serpent" (n = 3) la chiave derivata si divide in n chiavi primarie da 32 byte seguite da n chiavi secondarie da 32 byte. La **prima** chiave è usata da **Serpent**, la seconda da Twofish, la terza da AES: l'ordine delle chiavi è l'inverso del nome, cioè l'ordine con cui i cifrari vengono applicati in cifratura.
- Ordine di applicazione **[oracolo, confermato]**: in cifratura Serpent, poi Twofish, poi AES (ognuno in XTS completo); in decifratura l'ordine è inverso. La master key (byte 256–511) ha la stessa disposizione: n chiavi primarie, poi n chiavi secondarie.

## Keyfile (pagina "Keyfiles")
- Vengono letti al massimo i primi 1 048 576 byte di ogni keyfile.
- Il pool KP è inizializzato a zero; per ogni keyfile il cursore riparte da 0 e l'hash H viene reinizializzato. Per ogni byte letto si aggiorna H senza finalizzarlo e si sommano modulo 256 i byte dello stato M nel pool, a partire dalla posizione del cursore (che torna a 0 alla fine del pool).
- La password viene estesa con zeri fino alla lunghezza del pool. **[oracolo, confermato]** Il pool si combina con la password per **somma modulo 256**, non in XOR come descrive la pagina "Keyfiles".
- **[oracolo, confermato]** H = CRC-32 IEEE (tabella riflessa 0xEDB88320, registro iniziale 0xFFFFFFFF). Lo stato aggiunto al pool è il registro **non finalizzato** (senza la negazione finale), in big-endian; registro e cursore ripartono da zero a ogni keyfile.
- **[oracolo, confermato]** Il pool è di 64 byte se la password è lunga al massimo 64 byte, di 128 byte se è più lunga (la 1.26 accetta fino a 128 byte).

## Password
- Codifica dei caratteri non ASCII **[oracolo, confermato]**: UTF-8 (`pàsswörd€`).
- Lunghezza massima: 128 byte UTF-8 (note di rilascio 1.26).

## Layout del container
- Header standard @0, header nascosto @65536, area dati @131072 (`encrypted_area_start` dell'header), backup standard @S−131072, backup nascosto @S−65536 (pagina "VeraCrypt Volume Format Specification").
- **[oracolo, confermato]** L'area cifrata dell'header (448 byte) si decifra come unità XTS numero 0.

## Area dati
- **[oracolo, confermato]** Il numero di unità XTS di un settore è il suo offset **assoluto** nel file container diviso per 512 (il primo settore dati è l'unità 256).

## Osservazioni sul corpus
- `VeraCrypt Format.exe /filesystem FAT` sceglie FAT16 per i container da 10 e 64 MiB. Un container FAT32 va creato più grande (in P2).

## Creazione di un volume standard [oracolo, VeraCrypt 1.26.29]
- Campi dell'header letti da container del corpus (`core/coffer-volume/examples/header_fields.rs`): versione 5, versione minima del programma 0x010B (267), flag 0, dimensione del settore 512, `encrypted_area_start` 131072, `volume_size` = `encrypted_area_size` = dimensione del file − 262144, `hidden_volume_size` 0.
- Header principale e di backup hanno **sali diversi** (stessa master key).
- Le aree degli header nascosti (@65536 e @S−65536) e il resto di ogni area header da 64 KiB dopo i primi 512 byte contengono byte casuali: un volume senza nascosto è indistinguibile da uno che lo contiene.
- Un container creato da VeraCrypt ha l'area dati indistinguibile da dati casuali (formattazione completa: zeri cifrati).
