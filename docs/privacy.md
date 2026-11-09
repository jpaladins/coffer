# Coffer: informativa sulla privacy / Privacy policy

*Ultimo aggiornamento / Last updated: 2026-11-09*

## Italiano

Coffer è un'app per aprire, modificare e creare container cifrati compatibili con VeraCrypt sul tuo telefono.

- **Nessuna raccolta di dati.** Coffer non raccoglie, non conserva su server e non condivide alcun dato personale. Non contiene pubblicità, analisi statistiche, tracciamento o SDK di terze parti.
- **Nessun accesso a internet.** L'app non richiede il permesso di accesso alla rete: non può inviare dati da nessuna parte.
- **I tuoi file restano dove li hai messi.** Coffer accede solo ai file che scegli tu tramite il selettore di sistema. Dell'elenco dei container salva solo il riferimento al file e un'etichetta, sul telefono. Salva anche, solo sul telefono, la data dell'ultimo accesso a ogni container: puoi disattivarla in Impostazioni ("Ricorda l'ultimo accesso"), e così le date salvate vengono cancellate. Le anteprime delle foto vengono create solo in memoria, mai scritte su disco, e spariscono quando il container si chiude.
- **Password e chiavi.** Le password non vengono mai salvate, tranne se attivi "Ricorda con l'impronta": in quel caso la password viene conservata sul telefono, cifrata con una chiave dell'Android Keystore utilizzabile solo dopo la verifica dell'impronta. Il PIN di blocco dell'app viene conservato solo come hash (PBKDF2), mai in chiaro.
- **Nessun backup automatico.** I dati dell'app sono esclusi dal backup di Android.
- **Permessi.** Coffer usa il permesso per i servizi in primo piano (per non interrompere una copia lunga) e la biometria del telefono, se scegli di usare l'impronta. Su Android 13 e successivi chiede anche, una sola volta, il permesso di mostrare notifiche: servono solo a mostrare l'avanzamento di un'importazione di file (quanti file e quanti dati, mai i nomi), il loro contenuto è nascosto nella schermata di blocco e puoi rifiutarle.

Domande: tramite le issue del repository del progetto.

## English

Coffer is an app to open, edit and create VeraCrypt-compatible encrypted containers on your phone.

- **No data collection.** Coffer does not collect, store on servers or share any personal data. It contains no ads, analytics, tracking or third-party SDKs.
- **No internet access.** The app does not request the network permission: it cannot send data anywhere.
- **Your files stay where you put them.** Coffer only accesses files you pick with the system picker. For the container list it stores only a reference to the file and a label, on the phone. It also stores, only on the phone, when each container was last opened: you can turn this off in Settings ("Remember last access"), which deletes the saved times. Photo previews are made in memory only, never written to disk, and are gone when the container locks.
- **Passwords and keys.** Passwords are never stored, unless you turn on "Remember with fingerprint": then the password is kept on the phone, encrypted with an Android Keystore key usable only after fingerprint verification. The app-lock PIN is stored only as a hash (PBKDF2), never in clear.
- **No automatic backup.** App data is excluded from Android backup.
- **Permissions.** Coffer uses the foreground-service permission (so a long copy is not interrupted) and the phone's biometrics, if you choose to use your fingerprint. On Android 13 and later it also asks once to show notifications: they only show the progress of a file import (how many files and how much data, never their names), their content is hidden on the lock screen, and you can refuse them.

Questions: through the issues of the project repository.
