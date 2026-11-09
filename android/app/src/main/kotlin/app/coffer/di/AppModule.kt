// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.di

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.coffer.data.DataStoreVaultStore
import app.coffer.data.FdOpener
import app.coffer.data.FfiNativeVaults
import app.coffer.data.NativeVaults
import app.coffer.data.SessionManager
import app.coffer.data.VaultStore
import app.coffer.platform.ContentResolverAccess
import app.coffer.platform.ContentResolverExporter
import app.coffer.platform.DocumentAccess
import app.coffer.platform.Exporter
import app.coffer.platform.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.coffer.security.LockGuard
import app.coffer.security.AppLock
import app.coffer.security.KeystoreRememberedPasswords
import app.coffer.security.RememberedPasswords
import app.coffer.security.SecuritySettings
import app.coffer.transfer.TransferManager
import app.coffer.settings.UiPrefs
import app.coffer.browser.ThumbnailCache
import app.coffer.browser.ThumbnailLoader

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun dataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { ctx.preferencesDataStoreFile("coffer") }

    @Provides @Singleton
    fun vaultStore(ds: DataStore<Preferences>): VaultStore = DataStoreVaultStore(ds)

    /** Previews in memory only, at most 1/8 of the app heap. */
    @Provides @Singleton
    fun thumbnailCache() = ThumbnailCache((Runtime.getRuntime().maxMemory() / 8).toInt())

    @Provides @Singleton
    fun thumbnailLoader(sessions: SessionManager, cache: ThumbnailCache) = ThumbnailLoader(sessions, cache, Dispatchers.IO)

    @Provides @Singleton
    fun uiPrefs(ds: DataStore<Preferences>) = UiPrefs(ds)

    @Provides @Singleton
    fun nativeVaults(): NativeVaults = FfiNativeVaults()

    @Provides @Singleton
    fun resolverAccess(@ApplicationContext ctx: Context) = ContentResolverAccess(ctx.contentResolver)

    @Provides
    fun fdOpener(a: ContentResolverAccess): FdOpener = a

    @Provides
    fun documentAccess(a: ContentResolverAccess): DocumentAccess = a

    @Provides
    fun exporter(@ApplicationContext ctx: Context): Exporter = ContentResolverExporter(ctx.contentResolver)

    @Provides @Singleton
    fun lockGuard() = LockGuard()

    @Provides @Singleton
    fun remembered(@ApplicationContext ctx: Context, ds: DataStore<Preferences>): RememberedPasswords =
        KeystoreRememberedPasswords(ctx, ds)

    @Provides @Singleton
    fun appLock(settings: SecuritySettings) = AppLock(settings) { System.currentTimeMillis() }

    @Provides @Singleton
    fun transfers(sessions: SessionManager, access: ContentResolverAccess, guard: LockGuard) =
        TransferManager(sessions, access, guard, Dispatchers.IO, CoroutineScope(SupervisorJob()))

    @Provides @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton
    fun sessions(
        store: VaultStore,
        nativeVaults: NativeVaults,
        opener: FdOpener,
        @ApplicationContext ctx: Context,
    ) = SessionManager(store, nativeVaults, opener, Dispatchers.IO) { kdfMemoryBudget(ctx) }

    /**
     * Argon2id may need up to 1 GiB. Allow at most 60% of currently available
     * memory so the low-memory killer does not take the app (spec §7).
     */
    private fun kdfMemoryBudget(ctx: Context): Long {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return 256L shl 20
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return minOf(1L shl 30, info.availMem * 6 / 10)
    }
}
