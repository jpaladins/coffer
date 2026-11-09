# Coffer 0.0.2a — Piano di implementazione del redesign

**Obiettivo:** applicare il redesign Material 3 Expressive descritto nella spec, con onboarding, lista container a card e browser file più ricco.

**Architettura:** prima il sistema grafico (`app.coffer.ui.theme`) e i componenti condivisi (`app.coffer.ui.components`), poi ogni schermata ricostruita su quella base. La logica nuova (ordinamento, ricerca, preferenze, miniature, onboarding) sta in funzioni pure o classi piccole testate sulla JVM. I ViewModel esistenti si estendono, non si riscrivono.

**Stack:** Kotlin 2.4, Jetpack Compose (BOM 2026.09.00, material3 con API Expressive sperimentali), Hilt, DataStore, JUnit/kotlinx-coroutines-test, emulatore Small_Phone API 37.

**Spec:** `docs/design/2026-11-08-coffer-ui-redesign.md`

## Vincoli globali
- Tutte le funzioni attuali restano e si comportano come oggi; `./gradlew :core:data:test :app:testDebugUnitTest` resta verde a ogni task.
- Temi chiaro e scuro; contrasto testo ≥ 4,5:1 (≥ 3:1 testo grande e icone).
- Touch target ≥ 48 dp; etichette TalkBack su ogni icona interattiva; layout utilizzabile con font al 200%.
- Nessun dato del container scritto fuori dal container: miniature e cache solo in memoria.
- Animazioni disattivate dal sistema ⇒ transizioni istantanee.
- minSdk 26, targetSdk 36, edge-to-edge; nessun permesso nuovo (mai INTERNET).
- Stringhe in `values/strings.xml` (EN) e `values-it/strings.xml` (IT), apostrofi IT escapati `\'`.
- Messaggi di commit semplici, senza riferimenti a strumenti; nessun riferimento simile in codice e documenti.
- Versione finale: `versionName "0.0.2a"`, `versionCode 5`.

## Rischi da tenere d'occhio
1. Container con migliaia di file: ordinamento, ricerca e griglia restano fluidi. `sortEntries` e `filterEntries` sono O(n log n) e vengono chiamate fuori dalla composizione, con `remember` su lista, criterio e query.
2. Immagine corrotta o enorme in griglia: nessun crash. `ThumbnailLoader` restituisce null su qualsiasi eccezione o `OutOfMemoryError`; è testato in Task 7.
3. Container bloccato (auto-lock) mentre le miniature caricano: nessuna lettura dopo la chiusura e cache svuotata; testato in Task 7.
4. Selezione multipla durante un import in corso: Elimina disabilitato come le altre modifiche (`canEdit`); testato in Task 6.
5. Aggiornamento dalla 0.0.1a con container già presenti: niente onboarding; testato in Task 4.

---

### Task 1: Tema Expressive, font e token

**File:**
- Crea: `app/src/main/res/font/manrope.ttf` (variabile, da github.com/sharanda/manrope, OFL)
- Crea: `app/src/main/kotlin/app/coffer/ui/theme/Type.kt`
- Crea: `app/src/main/kotlin/app/coffer/ui/theme/Shapes.kt`
- Crea: `app/src/main/kotlin/app/coffer/ui/theme/Contrast.kt`
- Modifica: `app/src/main/kotlin/app/coffer/ui/theme/Theme.kt`
- Modifica: `legal/THIRD_PARTY.md`, `app/src/main/assets/third_party.md` (riga Manrope, OFL-1.1)
- Test: `app/src/test/kotlin/app/coffer/ThemeContrastTest.kt`

**Interfacce:**
- Produce: `CofferTheme(dark: Boolean = isSystemInDarkTheme(), content)` che usa `MaterialExpressiveTheme(colorScheme, typography = CofferTypography, shapes = CofferShapes, motionScheme = MotionScheme.expressive())`.
- Produce: `val Manrope: FontFamily` con pesi 400/500/600/700/800 (`FontVariation.weight`).
- Produce: `val TabularNums: TextStyle` (`fontFeatureSettings = "tnum"`).
- Produce: `object CofferRadii { val card = 28.dp; val sheet = 32.dp; val field = 50.percent }`.
- Produce: `internal val LightColors: ColorScheme`, `internal val DarkColors: ColorScheme`, `fun contrastRatio(a: Color, b: Color): Double` (formula WCAG su luminanza relativa).

- [ ] **Passo 1: test di contrasto che fallisce**

```kotlin
class ThemeContrastTest {
    private fun pairs(s: ColorScheme) = listOf(
        s.onSurface to s.surface, s.onSurfaceVariant to s.surface,
        s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
        s.onTertiary to s.tertiary, s.onTertiaryContainer to s.tertiaryContainer,
        s.onSurface to s.surfaceContainerHigh, s.onError to s.error,
    )
    @Test fun light_pairs_meet_aa() = pairs(LightColors).forEach { (fg, bg) -> assertTrue(contrastRatio(fg, bg) >= 4.5) }
    @Test fun dark_pairs_meet_aa() = pairs(DarkColors).forEach { (fg, bg) -> assertTrue(contrastRatio(fg, bg) >= 4.5) }
    @Test fun ratio_black_white_is_21() = assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
}
```

- [ ] **Passo 2:** `./gradlew :app:testDebugUnitTest --tests "*ThemeContrastTest*"`. Atteso: FAIL (simboli non definiti).
- [ ] **Passo 3: implementazione.**
  - **Colori:** primary `#0B3D2E` (chiaro) e `#8FD5B7` (scuro); tertiary oro `#E8B04A` con onTertiary `#3A2700`. I `surfaceContainer*` restano quelli attuali, ritoccati solo se un test di contrasto fallisce.
  - **Tipografia:** tutti gli stili Material 3 in Manrope.
    - `display*` e `headline*`: peso 800, letterSpacing -0,5 sp.
    - `title*`: peso 700.
    - `body*`: peso 400.
    - `label*`: peso 600.
  - **Forme:** small 12 dp, medium 20 dp, large 28 dp, extraLarge 32 dp.
- [ ] **Passo 4:** stesso comando. Atteso: PASS; poi `./gradlew :app:assembleDebug` OK.
- [ ] **Passo 5: commit.** `feat(app): expressive theme with Manrope type and brand tokens`

### Task 2: Componenti condivisi

**File (uno per componente, ognuno con `@Preview` chiaro e scuro):**
- `app/src/main/kotlin/app/coffer/ui/components/CofferTopBar.kt`
- `app/src/main/kotlin/app/coffer/ui/components/EmptyState.kt`
- `app/src/main/kotlin/app/coffer/ui/components/StatusBanner.kt`
- `app/src/main/kotlin/app/coffer/ui/components/SettingsSection.kt`
- `app/src/main/kotlin/app/coffer/ui/components/ProgressCard.kt`
- `app/src/main/kotlin/app/coffer/ui/components/StepIndicator.kt`
- `app/src/main/kotlin/app/coffer/ui/components/HexBadge.kt`
- `app/src/main/kotlin/app/coffer/ui/components/Motion.kt`
- Sposta: `ui/ErrorText.kt` diventa un wrapper di `StatusBanner` (stessa firma pubblica, i chiamanti non cambiano).
- Test: `app/src/test/kotlin/app/coffer/MotionTest.kt`

**Interfacce (prodotte):**
- `CofferTopBar(title: String, scrollBehavior: TopAppBarScrollBehavior, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {})`: `LargeFlexibleTopAppBar`, titolo in `headlineMedium`.
- `EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null)`.
- `enum class BannerKind { Error, Warning, Info }`; `StatusBanner(kind: BannerKind, text: String, action: Pair<String, () -> Unit>? = null)`. I colori vengono da error/tertiary/secondaryContainer; ha `liveRegion = Polite` per TalkBack.
- `SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit)` su una card `surfaceContainerLow` con raggio `CofferRadii.card`.
- `SettingsItem(title: String, summary: String? = null, icon: ImageVector? = null, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null)`, alto almeno 56 dp.
- `ProgressCard(title: String, detail: String, progress: Float?, onStop: () -> Unit)`: con `progress` null usa un `LinearWavyProgressIndicator` indeterminato.
- `StepIndicator(steps: List<String>, current: Int)`.
- `HexBadge(icon: ImageVector, size: Dp = 48.dp, container: Color, content: Color)`: icona dentro `MaterialShapes.Hexagon`.
- `fun animationsEnabled(context: Context): Boolean`: legge `Settings.Global.ANIMATOR_DURATION_SCALE` (≠ 0).
- `@Composable fun rememberHaptics(): Haptics` con `confirm()` e `reject()`, che usano `HapticFeedbackConstants.CONFIRM`/`REJECT` da API 30 e `LONG_PRESS` sotto.
- `Modifier.pressScale()`: scala a 0,96 alla pressione con la molla `MotionScheme.fastSpatialSpec`; non fa nulla se le animazioni sono disattivate.

- [ ] **Passo 1: test che fallisce**

```kotlin
class MotionTest {
    @Test fun zero_scale_disables() = assertFalse(animationsEnabledFor(0f))
    @Test fun normal_scale_enables() = assertTrue(animationsEnabledFor(1f))
}
```

`animationsEnabledFor(scale: Float): Boolean` è la funzione pura usata da `animationsEnabled`.

- [ ] **Passo 2:** `--tests "*MotionTest*"` FAIL.
- [ ] **Passo 3:** implementare i componenti.
- [ ] **Passo 4:** test PASS; `assembleDebug` OK; le anteprime si renderizzano in Android Studio senza errori.
- [ ] **Passo 5: commit.** `feat(app): shared components for the redesign`

### Task 3: Preferenze di interfaccia e ultimo accesso

**File:**
- Crea: `app/src/main/kotlin/app/coffer/settings/UiPrefs.kt`
- Modifica: `app/src/main/kotlin/app/coffer/di/AppModule.kt` (provider)
- Test: `app/src/test/kotlin/app/coffer/UiPrefsTest.kt`

**Interfacce (prodotte):**

```kotlin
enum class BrowserLayout { List, Grid }
enum class SortKey { Name, Modified, Size }
data class SortOrder(val key: SortKey = SortKey.Name, val ascending: Boolean = true)
class UiPrefs(dataStore: DataStore<Preferences>) {
    val onboardingDone: Flow<Boolean>; suspend fun setOnboardingDone()
    val layout: Flow<BrowserLayout>; suspend fun setLayout(l: BrowserLayout)
    val sort: Flow<SortOrder>; suspend fun setSort(s: SortOrder)
    val rememberLastAccess: Flow<Boolean>          // default true
    suspend fun setRememberLastAccess(on: Boolean) // false ⇒ cancella tutti i timestamp
    val lastAccess: Flow<Map<String, Long>>         // id container → epoch ms
    suspend fun recordAccess(id: String, at: Long) // no-op se rememberLastAccess è false
    suspend fun forget(id: String)                  // alla rimozione del container
}
```

Chiavi DataStore: `ui_onboarding_done`, `ui_layout`, `ui_sort_key`, `ui_sort_asc`, `ui_remember_access`, `ui_last_access` (JSON `Map<String, Long>`).

- [ ] **Passo 1: test** (DataStore su file temporaneo, come `VaultStoreTest`):
  - `defaults_are_list_name_ascending_remember_on`
  - `record_then_read_last_access`
  - `disabling_remember_clears_all_timestamps` (dopo `setRememberLastAccess(false)`, `lastAccess.first()` è vuota)
  - `record_ignored_when_disabled`
  - `forget_removes_one_id`
  - `sort_and_layout_round_trip`
- [ ] **Passo 2:** FAIL. **Passo 3:** implementare. **Passo 4:** PASS.
- [ ] **Passo 5: integrazione.**
  - `UnlockViewModel` chiama `recordAccess(id, System.currentTimeMillis())` dopo uno sblocco riuscito.
  - `VaultsViewModel.onRemove` chiama `forget(id)`.
  - Il test in `VaultsAndUnlockViewModelTest` verifica che un timestamp esista dopo lo sblocco.
- [ ] **Passo 6: commit.** `feat(app): interface preferences and last access`

### Task 4: Onboarding

**File:**
- Crea: `app/src/main/kotlin/app/coffer/onboarding/OnboardingScreen.kt`
- Crea: `app/src/main/kotlin/app/coffer/onboarding/OnboardingGate.kt`
- Modifica: `nav/CofferNavHost.kt` (route `onboarding`, destinazione iniziale decisa dal gate)
- Modifica: `MainActivity.kt` (`askNotificationsOnce()` solo dopo l'onboarding)
- Stringhe: titoli e testi delle 3 pagine, "Salta", "Avanti", "Aggiungi container", "Crea container"
- Test: `app/src/test/kotlin/app/coffer/OnboardingGateTest.kt`

**Interfacce:**
- Consuma: `UiPrefs.onboardingDone`, `UiPrefs.setOnboardingDone()`, `VaultStore.vaults`.
- Produce: `fun shouldShowOnboarding(done: Boolean, vaultCount: Int): Boolean = !done && vaultCount == 0`.
- Produce: `OnboardingScreen(onFinish: (OnboardingExit) -> Unit)` con `enum class OnboardingExit { Skip, AddExisting, CreateNew }`.
  - Usa un `HorizontalPager` con 3 pagine e un indicatore di pagina.
  - L'illustrazione è un `HexBadge` grande con icona Lock / VisibilityOff / FolderOpen.
  - `AddExisting` apre il picker esistente della lista; `CreateNew` naviga a `Routes.CREATE`. Tutte e tre le uscite chiamano `setOnboardingDone()`.

- [ ] **Passo 1: test**
  - `new_user_sees_onboarding`: `shouldShowOnboarding(false, 0) == true`
  - `upgrade_with_vaults_skips`: `shouldShowOnboarding(false, 2) == false`
  - `done_never_shows`: `shouldShowOnboarding(true, 0) == false`
- [ ] **Passo 2–4:** FAIL, implementazione, PASS.
- [ ] **Passo 5: emulatore** (`pm clear com.coffer.app`):
  - l'onboarding appare;
  - "Salta" porta alla lista;
  - riavviando non riappare;
  - la richiesta notifiche arriva dopo l'onboarding;
  - con il blocco app attivo, compare prima il blocco.
- [ ] **Passo 6: commit.** `feat(app): first-run onboarding`

### Task 5: Lista container a card

**File:**
- Crea: `app/src/main/kotlin/app/coffer/vaults/VaultCard.kt`
- Crea: `app/src/main/kotlin/app/coffer/vaults/RelativeTime.kt`
- Modifica: `vaults/VaultsViewModel.kt`, `vaults/VaultsScreen.kt`
- Modifica: `core/data/.../SessionManager.kt`: aggiungere `fun fileSize(uri: String): Long?` a `DocumentAccess` (default null) e implementarla in `platform/ContentResolverAccess.kt` con una query `OpenableColumns.SIZE`
- Test: `app/src/test/kotlin/app/coffer/VaultsAndUnlockViewModelTest.kt` (estendere), `app/src/test/kotlin/app/coffer/RelativeTimeTest.kt`

**Interfacce:**
- Consuma: `UiPrefs.lastAccess`, `SessionManager.session(id)?.handle?.info` (`fsKind`, `size`), `handle.freeBytes()`.
- Produce:

```kotlin
data class VaultItem(
    val id: String, val label: String, val isOpen: Boolean,
    val fileBytes: Long? = null,        // dimensione del file container
    val fsKind: String? = null,         // solo se aperto
    val freeBytes: Long? = null,        // solo se aperto
    val volumeBytes: Long? = null,      // solo se aperto
    val lastAccess: Long? = null,
)
fun relativeTime(now: Long, then: Long, res: Resources): String
```

Fasce di `relativeTime`: "adesso" sotto 1 minuto; minuti, ore e giorni sotto 7 giorni; oltre, la data breve della locale.

- `VaultCard(item, onOpen, onLock, onRemove, onDetails)`:
  - `HexBadge` e titolo in `titleLarge`;
  - badge "Aperto" su tertiaryContainer oppure "Chiuso" su surfaceVariant;
  - se aperto: file system, `LinearProgressIndicator` dello spazio usato e testo "x liberi di y" in `TabularNums`;
  - se chiuso: "Ultimo accesso …";
  - menu con Dettagli e Rimuovi dalla lista;
  - "Dettagli" apre un bottom sheet con nome, dimensione e, se aperto, cifrario, PRF e file system.
- FAB esteso "Aggiungi" apre un `ModalBottomSheet` con "Aggiungi esistente" e "Crea nuovo"; lo stato vuoto usa `EmptyState`.
- Le card compaiono sfalsate di 40 ms solo al primo caricamento.

- [ ] **Passo 1: test**
  - `RelativeTimeTest`: 30 s ⇒ adesso; 5 min; 3 h; 2 giorni; 10 giorni ⇒ data.
  - `open_item_has_free_space_and_fs`: con una sessione aperta finta, `freeBytes` e `fsKind` valorizzati.
  - `closed_item_has_last_access_from_prefs`.
- [ ] **Passo 2–4:** FAIL, implementazione, PASS.
- [ ] **Passo 5: emulatore**, chiaro e scuro, con un container aperto e uno chiuso; screenshot.
- [ ] **Passo 6: commit.** `feat(app): container cards with status, space and last access`

### Task 6: Browser — ordinamento, ricerca, griglia, selezione multipla

**File:**
- Crea: `app/src/main/kotlin/app/coffer/browser/EntrySort.kt`
- Crea: `app/src/main/kotlin/app/coffer/browser/FileTile.kt`
- Crea: `app/src/main/kotlin/app/coffer/browser/FileRow.kt` (estratto da `BrowserScreen.kt`)
- Crea: `app/src/main/kotlin/app/coffer/browser/SelectionBar.kt`
- Modifica: `browser/BrowserViewModel.kt`, `browser/BrowserScreen.kt`
- Modifica: `platform/Exporter.kt`: aggiungere `fun createInTree(treeUri: String, name: String, mime: String): String` all'interfaccia `Exporter`, implementata con `DocumentsContract.createDocument` su `buildDocumentUriUsingTree`
- Test: `app/src/test/kotlin/app/coffer/EntrySortTest.kt`, `app/src/test/kotlin/app/coffer/BrowserWriteTest.kt` (estendere)

**Interfacce:**
- Consuma: `UiPrefs.layout/sort/setLayout/setSort`, `SortOrder`, `BrowserLayout`.
- Produce:

```kotlin
fun sortEntries(entries: List<Entry>, order: SortOrder): List<Entry>    // cartelle sempre prima
fun filterEntries(entries: List<Entry>, query: String): List<Entry>     // Normalizer NFD, senza segni, lowercase
```

- Campi aggiunti a `BrowserUiState`:
  - `query: String = ""` e `searchOpen: Boolean = false`;
  - `selected: Set<String> = emptySet()` (nomi nella cartella corrente);
  - `layout: BrowserLayout` e `sort: SortOrder`;
  - valore calcolato `visible: List<Entry>` = `sortEntries(filterEntries(entries, query), sort)`.
- Nuove azioni del ViewModel:
  - `onQuery(q)`, `onToggleSearch()`, `onSort(order)`, `onLayout(l)`;
  - selezione: `onToggleSelect(name)`, `onClearSelection()`;
  - `onDeleteSelected()`, che rispetta `canEdit` e svuota la selezione;
  - `onExportSelected(treeUri: String)`, che esporta in sequenza con la logica di `onExport` e un avanzamento complessivo.
- Cambiare cartella svuota selezione e query.

**UI:**
- La ricerca si espande nell'header.
- Menu ordinamento con le 3 chiavi e la direzione; interruttore lista/griglia.
- Griglia adattiva con `GridCells.Adaptive(112.dp)`.
- Pressione prolungata: selezione con `SelectionBar` al posto della top bar ("n selezionati", Esporta, Elimina con dialog di conferma).
- Breadcrumb a chip scorrevoli; import in corso mostrato con `ProgressCard`.

- [ ] **Passo 1: test**
  - `folders_first_then_name_case_insensitive`
  - `size_descending_keeps_folders_first`
  - `modified_null_sorts_last`
  - `search_ignores_case_and_accents`: "perche" trova "Perché.txt"
  - `empty_query_returns_all`
  - `delete_selected_removes_all_and_clears_selection`
  - `delete_selected_blocked_during_import`: con `transfer != null` non elimina nulla
  - `navigating_clears_selection_and_query`
- [ ] **Passo 2–4:** FAIL, implementazione, PASS.
- [ ] **Passo 5: emulatore.**
  - Container con 500 file generati: lo scroll resta fluido sia in griglia sia in lista.
  - Selezione multipla, esportazione in una cartella ed eliminazione funzionano.
- [ ] **Passo 6: commit.** `feat(app): browser sort, search, grid and multi-select`

### Task 7: Miniature in memoria

**File:**
- Crea: `app/src/main/kotlin/app/coffer/browser/ThumbnailCache.kt`
- Crea: `app/src/main/kotlin/app/coffer/browser/ThumbnailLoader.kt`
- Modifica: `CofferApp.kt` (svuota la cache nel close listener della sessione)
- Modifica: `browser/FileTile.kt`, `browser/FileRow.kt`
- Test: `app/src/test/kotlin/app/coffer/ThumbnailCacheTest.kt`
- Test: `app/src/androidTest/kotlin/app/coffer/ThumbnailNoDiskTest.kt`

**Interfacce (prodotte):**

```kotlin
class ThumbnailCache(maxBytes: Int) {               // LruCache<String, ImageBitmap>, sizeOf = byteCount
    fun get(vaultId: String, path: String): ImageBitmap?
    fun put(vaultId: String, path: String, bmp: ImageBitmap)
    fun clear(vaultId: String)
}
class ThumbnailLoader(sessions: SessionManager, cache: ThumbnailCache, io: CoroutineDispatcher) {
    suspend fun load(vaultId: String, path: String, size: Long, targetPx: Int): ImageBitmap?
}
const val THUMB_MAX_FILE = 30L shl 20
```

**Comportamento di `load`:**
- restituisce null se `size > THUMB_MAX_FILE`, se l'estensione non è jpg, jpeg, png, webp o heic, oppure se la sessione è chiusa;
- legge i byte con `handle.read` a blocchi da `MAX_IO`;
- decodifica in due passaggi (`inJustDecodeBounds`, poi `inSampleSize` fino al lato minimo ≥ `targetPx`);
- intercetta `Exception` e `OutOfMemoryError` e restituisce null;
- dopo la lettura ricontrolla che la sessione sia ancora aperta, altrimenti scarta.

**Altre regole:**
- La cache è un singleton Hilt con `maxBytes` = `Runtime.maxMemory() / 8`.
- Le miniature si caricano nella composizione con `produceState` sulla chiave (vault, percorso), con al massimo 2 decodifiche in parallelo (`Semaphore(2)`).
- La miniatura compare in dissolvenza.

- [ ] **Passo 1: test JVM** (con un loader iniettabile per evitare `BitmapFactory`, oppure solo sulla cache):
  - `clear_removes_only_that_vault`
  - `eviction_respects_max_bytes`
  - `loader_returns_null_over_size_limit`
  - `loader_returns_null_when_session_closed_after_read`
- [ ] **Passo 2: test strumentale `ThumbnailNoDiskTest`**:
  - elenca ricorsivamente `filesDir`, `cacheDir`, `codeCacheDir` ed `externalCacheDir`;
  - carica 5 miniature da un container di test;
  - rielenca: l'insieme dei file è identico.
- [ ] **Passo 3–4:** implementazione; test JVM PASS; `connectedDebugAndroidTest` PASS.
- [ ] **Passo 5: commit.** `feat(app): in-memory photo thumbnails`

### Task 8: Sblocco

**File:** `unlock/UnlockScreen.kt` (stessi ViewModel e test).

- Header con `HexBadge` grande e nome del container in `headlineMedium`.
- Campo password a pillola con mostra/nascondi.
- "Opzioni avanzate" (PIM, keyfile, KDF, header di backup) in una sezione espandibile animata, aperta automaticamente se il container ha già un `lastPrf` diverso dal predefinito o se uno di quei campi è valorizzato.
- Pulsante "Sblocca" a tutta larghezza (alto 56 dp, colore tertiary): durante lo sblocco il testo diventa `LoadingIndicator`; feedback tattile `confirm` al successo e `reject` all'errore.
- Errori mostrati con `StatusBanner(Error)`; l'avviso "Volume non chiuso correttamente" con `StatusBanner(Warning)` e le due azioni attuali.
- [ ] Verifica: `UnlockSecurityTest` e `VaultsAndUnlockViewModelTest` verdi; screenshot chiaro e scuro con font al 200% senza testo tagliato.
- [ ] Commit: `feat(app): redesigned unlock screen`

### Task 9: Creazione a passi

**File:**
- Modifica: `create/CreateScreen.kt`; eventuale divisione in `create/steps/*.kt`, un file per passo
- Modifica: `create/CreateViewModel.kt` (solo stato del passo)
- Test: `CreateViewModelTest.kt` (estendere)

**Interfacce:**
- Produce: `enum class CreateStep { Location, Size, Password, Review }`, `val step: CreateStep` nello stato, e le azioni `onNext()` e `onBack()`.
- `onNext()` avanza solo se il passo corrente è valido:
  - `Location`: nome non vuoto e destinazione scelta;
  - `Size`: dimensione entro i limiti attuali;
  - `Password`: password e conferma uguali, regole attuali.
- `Review` riassume tutto; "Crea" avvia la logica esistente.
- `StepIndicator` in alto; i passi scorrono orizzontalmente con le animazioni di sistema.
- La forza della password è mostrata con una barra a 4 segmenti e un testo (riusa `PasswordStrength.kt`).
- Durante la creazione: `ProgressCard` con percentuale e stima del tempo rimanente in `TabularNums`.
- [ ] Test:
  - `next_blocked_until_location_valid`
  - `next_blocked_when_passwords_differ`
  - `back_from_review_keeps_values`
- [ ] Verifica: creazione completa sull'emulatore; il container creato si apre.
- [ ] Commit: `feat(app): step-by-step container creation`

### Task 10: Visualizzatore, impostazioni, blocco app, info

**File:**
- `viewer/ViewerScreen.kt`
- `settings/SettingsScreen.kt`
- `security/LockScreen.kt`
- `about/AboutScreen.kt`
- stringhe per "Ricorda l'ultimo accesso"

**Visualizzatore:**
- sfondo nero;
- barre che si nascondono con un tocco (e anche le barre di sistema, tramite `WindowInsetsControllerCompat`);
- immagini con zoom a pinch, pan e doppio tocco (2,5× al punto toccato), implementato con `detectTransformGestures` senza librerie nuove.

**Impostazioni:**
- `SettingsSection` "Sicurezza": blocco app, timeout, schermate bloccate, password ricordate.
- `SettingsSection` "Privacy": "Ricorda l'ultimo accesso", collegata a `UiPrefs.setRememberLastAccess`.
- `SettingsSection` "Informazioni": voce che apre Info.

**Blocco app:**
- tastierino PIN personalizzato 3×4 con tasti circolari da 72 dp e `pressScale`;
- 4–8 punti che si riempiono con una molla e tremano all'errore (`reject`);
- pulsante impronta in evidenza quando disponibile.

**Info:** le sezioni attuali su card, senza perdere il credito JPaladins.

- [ ] Test: in `AppLockTest` restano verdi; nuovo `remember_access_toggle_clears_prefs` in `UiPrefsTest` se non già coperto.
- [ ] Verifica: screenshot di ogni schermata in chiaro e scuro; TalkBack legge le cifre e "Cancella" sul tastierino.
- [ ] Commit: `feat(app): redesigned viewer, settings, app lock and about`

### Task 11: Rifinitura, accessibilità e controllo finale

- Passaggio su tutte le schermate:
  - nessun valore di colore o dimensione scritto a mano fuori dal tema;
  - spaziature su griglia di 4 dp;
  - icone `contentDescription` o `null` solo se decorative;
  - target ≥ 48 dp.
- Lint: nessun nuovo avviso nei file toccati (`./gradlew :app:lintDebug`, da confrontare con gli errori già noti: `local.properties`, `ContextCastToActivity`, `app_name`).
- Matrice di screenshot sull'emulatore, salvata in `docs/qa/0.0.2a-screens/`:
  - schermate: lista, sblocco, browser (lista e griglia), selezione, visualizzatore, creazione (4 passi), impostazioni, blocco, info, onboarding (3 pagine);
  - per ciascuna, chiaro e scuro, font al 100% e al 200%.
- "Rimuovi animazioni" attivo: si naviga senza animazioni e senza crash.
- [ ] Verifica: suite completa `:core:data:test :app:testDebugUnitTest :app:connectedDebugAndroidTest` verde.
- [ ] Commit: `chore(app): redesign polish and accessibility pass`

### Task 12: Release 0.0.2a

- `android/app/build.gradle.kts`: `versionCode = 5`, `versionName = "0.0.2a"`.
- `fastlane/metadata/android/{en-US,it-IT}/changelogs/5.txt`: nuova interfaccia, onboarding, card dei container, ricerca, ordinamento, griglia con anteprime, selezione multipla.
- Rigenerare screenshot e grafiche dello store (`scripts/store-screens.py`, `scripts/store-graphics.py`) e aggiornare `docs/store/raw/`.
- `./gradlew :app:bundleRelease` e copia in `release/coffer-0.0.2a.aab`; verificare con `keytool -printcert` la stessa impronta SHA-256 (`F4:2E:20:3B:…:AC:73`) e con `aapt2 dump badging` `versionCode='5'`.
- `scripts/check-reproducible.ps1`: due build pulite producono lo stesso hash.
- [ ] Commit: `build: 0.0.2a (versionCode 5)`, poi push.
