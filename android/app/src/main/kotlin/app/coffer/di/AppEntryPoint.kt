// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.di

import app.coffer.data.SessionManager
import app.coffer.platform.Exporter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Access to singletons from non-injected code (instrumented tests). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun sessions(): SessionManager
    fun exporter(): Exporter
    fun settings(): app.coffer.security.SecuritySettings
}
