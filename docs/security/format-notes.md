# Note sul formato VeraCrypt (clean-room)

Raccolte **solo** dalla documentazione pubblica (`C:\Program Files\VeraCrypt\docs\html\en`, versione 1.26.29) e dal comportamento osservato. Le voci marcate **[oracolo]** non sono specificate in modo univoco dalla documentazione e sono state risolte con i test sul corpus (`corpus/`).

## Derivazione della chiave dell'header (pagina "Header Key Derivation")
- Salt: 64 byte (512 bit), in chiaro all'inizio dell'header.
- KDF: PBKDF2-HMAC con SHA-512, SHA-256, BLAKE2s-256, Whirlpool o Streebog; oppure Argon2id.
- Output: 192 byte. Ogni cifrario usa il prefisso necessario (64 byte per cifrario: 32 di chiave primaria e 32 di chiave secondaria XTS).
- Iterazioni PBKDF2 (volumi non di sistema e file container): PIM = 0 → 500 000; PIM > 0 → 15 000 + PIM × 1000.
- Argon2id: parallelismo 1. Con PIM > 0: m_cost = min(64 + (PIM − 1) × 32, 1024) MiB; t_cost = 3 + ⌊(PIM − 1) / 3⌋ se PIM ≤ 31, altrimenti 13 + (PIM − 31). Con PIM = 0 si usano i parametri di PIM = 12 (416 MiB, 6 iterazioni). Vanno richiesti sempre 192 byte, perché la lunghezza dell'output entra nel calcolo di Argon2.
- Argon2id, dettagli **[oracolo]**: versione 0x13, nessun secret né associated data; salt = i 64 byte del salt dell'header.

## Cascate (pagina "Header Key Derivation")
- Nella cascata "AES-Twofish-Serpent" (n = 3) la chiave derivata si divide in n chiavi primarie da 32 byte seguite da n chiavi secondarie da 32 byte. La **prima** chiave è usata da **Serpent**, la seconda da Twofish, la terza da AES: l'ordine delle chiavi è l'inverso del nome, cioè l'ordine con cui i cifrari vengono applicati in cifratura.
- Ordine di applicazione **[oracolo]**: in cifratura Serpent, poi Twofish, poi AES (ognuno in XTS completo); in decifratura l'ordine è inverso.

## Keyfile (pagina "Keyfiles")
- Vengono letti al massimo i primi 1 048 576 byte di ogni keyfile.
- Il pool KP è inizializzato a zero; per ogni keyfile il cursore riparte da 0 e l'hash H viene reinizializzato. Per ogni byte letto si aggiorna H senza finalizzarlo e si sommano modulo 256 i byte dello stato M nel pool, a partire dalla posizione del cursore (che torna a 0 alla fine del pool).
- La password viene estesa con zeri fino alla lunghezza del pool, poi B_i ^= G_i.
- Dettagli **[oracolo]**: H = CRC-32 (stato di 4 byte); valore e ordine dei byte dello stato; dimensione del pool (64 secondo la documentazione, che però descrive password fino a 64 byte, mentre la 1.26 ne accetta 128).

## Password
- Codifica dei caratteri non ASCII **[oracolo]**: UTF-8.
