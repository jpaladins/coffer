# Coffer 0.1.0a — Redesign dell'interfaccia

**Obiettivo:** un'interfaccia moderna e professionale, curata nei dettagli, con lo stile Material 3 Expressive, mantenendo l'identità di Coffer (icona esagonale, verde bosco `#0B3D2E` e oro `#E8B04A`, vedi `docs/brand/brand.md`).

**Fuori ambito:** nuove funzioni crittografiche o di file system, cambi al formato dei volumi, permessi nuovi (resta senza INTERNET), Material You (resta disattivato).

## Vincoli
- Tutte le funzioni attuali restano e si comportano come oggi; i test esistenti restano verdi.
- Temi chiaro e scuro; contrasto testo almeno WCAG AA (4,5:1, 3:1 per testo grande e icone).
- Touch target di almeno 48 dp; tutto usabile con TalkBack e con il font di sistema al 200%.
- Nessun dato del container scritto fuori dal container (miniature e cache solo in memoria).
- "Rimuovi animazioni" del sistema rispettato: con animazioni disattivate le transizioni diventano istantanee.
- Funziona da minSdk 26 a targetSdk 36, edge-to-edge su tutte le schermate.
- Versione 0.1.0a, versionCode successivo all'ultimo caricato su Play.

## 1. Sistema grafico (`app.coffer.ui.theme`, `app.coffer.ui.components`)

**Colori.** Schema chiaro e scuro derivato dal verde bosco (primary) e dall'oro (tertiary/accento). L'oro è riservato alle azioni principali (FAB, pulsante "Sblocca"), allo stato "aperto" e agli indicatori di avanzamento. Superfici a livelli (`surfaceContainer*`) per separare le card dallo sfondo senza bordi né divisori.

**Tipografia.** Manrope (variabile, OFL) inclusa come risorsa font; scala tipografica Material 3 completa con titoli `display`/`headline` in peso 700–800 e testi in 400–500. Numeri tabellari (`fontFeatureSettings = "tnum"`) per dimensioni, spazio libero e avanzamenti. Licenza aggiunta a `legal/THIRD_PARTY.md` e all'asset delle licenze.

**Forme.** Card con raggio 28 dp, campi e pulsanti a pillola, contenitori di icone con le forme di `MaterialShapes` (incluso un esagono coerente con il logo). Le forme dei pulsanti si trasformano alla pressione (Expressive).

**Movimento.** `MotionScheme.expressive()` come base: molle per comparsa delle card, pressione, espansione della ricerca; transizioni tra schermate con scorrimento e dissolvenza condivisa; comparsa sfalsata degli elementi delle liste solo al primo caricamento. Feedback tattile leggero su azioni confermate (sblocco riuscito, import completato, eliminazione). Nessuna animazione su ogni battuta di tastiera o su dati che cambiano spesso.

**Componenti condivisi** (ognuno in un proprio file, con anteprima Compose in chiaro e scuro):
- `CofferTopBar`: header grande che si riduce scorrendo.
- `VaultCard`: card del container (vedi §3).
- `FileRow` e `FileTile`: elemento file in lista e in griglia.
- `EmptyState`: illustrazione vettoriale, titolo, testo, azione.
- `StatusBanner`: errore, avviso, informazione, con icona e azione opzionale.
- `SettingsSection` e `SettingsItem`: gruppi di impostazioni su card.
- `ProgressCard`: avanzamento dell'import con nome, contatore e pulsante Stop.
- `StepIndicator`: indicatore dei passi per la creazione del container.

## 2. Onboarding (nuovo)
- Tre pagine al primo avvio, con indicatore di pagina e "Salta":
  1. Cos'è Coffer.
  2. Privacy: niente internet, password solo in memoria.
  3. Come iniziare.
- L'ultima pagina ha "Aggiungi container" e "Crea container"; entrambi chiudono l'onboarding e portano all'azione scelta.
- Mostrato una sola volta: flag `onboarding_done` nelle preferenze. Non compare se l'utente ha già almeno un container (aggiornamento dalla 0.0.1a).
- La richiesta del permesso notifiche al primo avvio avviene dopo la chiusura dell'onboarding.
- Il blocco dell'app, se attivo, ha la precedenza sull'onboarding.

## 3. Lista container
- `VaultCard` per container:
  - icona a esagono;
  - nome;
  - dimensione del file;
  - badge di stato "Aperto" (oro) o "Chiuso".
- Card di un container aperto:
  - file system (FAT/exFAT);
  - spazio libero con barra;
  - azione "Blocca".
- Card di un container chiuso:
  - "Ultimo accesso" in forma relativa (es. "2 ore fa");
  - un tocco apre lo sblocco.
- **Ultimo accesso:** il timestamp viene salvato al momento dello sblocco, solo in locale. Una nuova impostazione "Ricorda l'ultimo accesso" (attiva di default) lo disattiva e cancella i valori salvati.
- **Altre azioni:** menu della card con "Rimuovi dalla lista" e "Dettagli". Il FAB esteso "Aggiungi" apre un foglio con "Aggiungi esistente" e "Crea nuovo". Stato vuoto con `EmptyState`.

## 4. Browser file
- Viste lista e griglia, scelta ricordata per tutta l'app.
- Miniature per immagini (JPEG, PNG, WebP, HEIC dove supportato):
  - decodificate dal container in memoria con `inSampleSize`;
  - cache LRU in memoria limitata a 1/8 della memoria dell'app;
  - cache svuotata alla chiusura del container;
  - mai scritte su disco;
  - caricate solo per gli elementi visibili, un file alla volta in background;
  - per i file oltre 30 MB nessuna miniatura, solo icona.
- Ordinamento per nome, data di modifica o dimensione, crescente o decrescente. Le cartelle stanno sempre in cima e la scelta viene ricordata.
- Ricerca nella cartella corrente: filtro per nome che non distingue maiuscole e accenti, con barra che si espande nell'header.
- Selezione multipla con pressione prolungata:
  - barra contestuale con conteggio, "Esporta" ed "Elimina" (con conferma);
  - l'esportazione multipla chiede una cartella di destinazione (SAF `OpenDocumentTree`).
- Percorso a "chip" scorrevoli; import in corso mostrato con `ProgressCard`.

## 5. Altre schermate (stesse funzioni, nuova veste)
- **Sblocco:**
  - header con icona e nome del container;
  - campi a pillola;
  - opzioni avanzate (PIM, keyfile, KDF) in una sezione espandibile;
  - pulsante "Sblocca" a tutta larghezza con indicatore di caricamento al posto del testo;
  - errori in `StatusBanner`.
- **Creazione:** procedura a passi con `StepIndicator`:
  1. Nome e posizione.
  2. Dimensione e file system.
  3. Password.
  4. Riepilogo.
  La forza della password è mostrata con barra e testo; durante la creazione c'è un avanzamento con stima.
- **Visualizzatore:** sfondo scuro a tutto schermo, barre che si nascondono al tocco, zoom con doppio tocco e pinch per le immagini.
- **Impostazioni:** sezioni su card (Sicurezza, Privacy, Informazioni), interruttori e scelte con descrizione.
- **Blocco app:** tastierino PIN personalizzato con punti animati e accesso biometrico in evidenza.
- **Info:** stessa struttura di oggi con le nuove card e il credito JPaladins.
- **Notifica di import:** invariata.

## 6. Verifica
- **Test unitari nuovi:**
  - ordinamento (cartelle in cima, tre criteri, due direzioni);
  - ricerca (maiuscole e accenti);
  - onboarding mostrato una sola volta e saltato se esistono container;
  - cancellazione dell'ultimo accesso quando l'impostazione viene disattivata;
  - cache delle miniature svuotata alla chiusura del container.
- **Test strumentali nuovi:**
  - nessun file creato nella cache o nei file dell'app durante il caricamento delle miniature;
  - selezione multipla ed eliminazione.
- **Screenshot sull'emulatore** di ogni schermata:
  - chiaro e scuro;
  - telefono piccolo (Small_Phone);
  - font al 200%;
  - TalkBack: etichette su tutte le icone.
- **Store:** al termine, rifare screenshot e grafiche (`scripts/store-screens.py`, `scripts/store-graphics.py`) e aggiornare le note di rilascio.
