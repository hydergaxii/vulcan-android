package com.vulcan.app.di

import android.content.Context
import com.vulcan.app.backup.BackupManager
import com.vulcan.app.data.database.VulcanDatabase
import com.vulcan.app.data.database.dao.*
import com.vulcan.app.icon.IconResolver
import com.vulcan.app.launcher.VulcanLauncherBridge
import com.vulcan.app.monitoring.MetricsCollector
import com.vulcan.app.network.MDNSAdvertiser
import com.vulcan.app.network.VulcanConnect
import com.vulcan.app.network.VulcanProxy
import com.vulcan.app.runtime.*
import com.vulcan.app.security.*
import com.vulcan.app.store.StoreRepository
import com.vulcan.app.storage.StorageManager
import com.vulcan.app.web.VulcanWebServer
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VulcanModule {

    @Provides @Singleton
    fun provideGson(): Gson = Gson()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── Database ──────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): VulcanDatabase =
        VulcanDatabase.getInstance(ctx)

    @Provides @Singleton fun provideAppDao(db: VulcanDatabase): AppDao = db.appDao()
    @Provides @Singleton fun provideSlotDao(db: VulcanDatabase): SlotDao = db.slotDao()
    @Provides @Singleton fun provideMetricsDao(db: VulcanDatabase): MetricsDao = db.metricsDao()
    @Provides @Singleton fun provideAuditDao(db: VulcanDatabase): AuditDao = db.auditDao()

    // ── Runtime ───────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideRuntimeEngine(@ApplicationContext ctx: Context): RuntimeEngine = RuntimeEngine(ctx)

    @Provides @Singleton
    fun providePortManager(@ApplicationContext ctx: Context): PortManager = PortManager(ctx)

    @Provides @Singleton
    fun provideRuntimeDownloader(@ApplicationContext ctx: Context): RuntimeDownloader = RuntimeDownloader(ctx)

    // ── Network ───────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideVulcanProxy(): VulcanProxy = VulcanProxy()

    @Provides @Singleton
    fun provideMDNSAdvertiser(@ApplicationContext ctx: Context): MDNSAdvertiser = MDNSAdvertiser(ctx)

    @Provides @Singleton
    fun provideVulcanConnect(@ApplicationContext ctx: Context): VulcanConnect = VulcanConnect(ctx)

    @Provides @Singleton
    fun provideVulcanWebServer(@ApplicationContext ctx: Context): VulcanWebServer = VulcanWebServer(ctx)

    // ── Security ──────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideSecretsVault(@ApplicationContext ctx: Context): SecretsVault = SecretsVault(ctx)

    @Provides @Singleton
    fun provideDashboardAuth(@ApplicationContext ctx: Context): DashboardAuth = DashboardAuth(ctx)

    @Provides @Singleton
    fun provideManifestVerifier(): ManifestVerifier = ManifestVerifier()

    @Provides @Singleton
    fun provideAppIsolationManager(@ApplicationContext ctx: Context): AppIsolationManager = AppIsolationManager(ctx)

    // ── Icon ──────────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideIconResolver(@ApplicationContext ctx: Context): IconResolver = IconResolver(ctx)

    // ── Launcher ──────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideLauncherBridge(@ApplicationContext ctx: Context): VulcanLauncherBridge = VulcanLauncherBridge(ctx)

    // ── Monitoring ────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideMetricsCollector(@ApplicationContext ctx: Context): MetricsCollector = MetricsCollector(ctx)

    // ── Backup ────────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideBackupManager(@ApplicationContext ctx: Context): BackupManager = BackupManager(ctx)

    // ── Store ─────────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideStoreRepository(
        @ApplicationContext ctx: Context,
        client: OkHttpClient,
        verifier: ManifestVerifier,
        gson: Gson
    ): StoreRepository = StoreRepository(ctx, client, verifier, gson)
}
