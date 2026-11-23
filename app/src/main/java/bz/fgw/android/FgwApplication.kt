package bz.fgw.android

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import bz.fgw.android.data.AppRepository
import bz.fgw.android.data.WatchDatabase
import bz.fgw.android.data.MIGRATION_2_3
import bz.fgw.android.data.MIGRATION_1_2
import bz.fgw.android.data.MIGRATION_3_4
import bz.fgw.android.data.MIGRATION_4_5
import bz.fgw.android.data.MIGRATION_5_6
import kotlinx.coroutines.Dispatchers
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FgwApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }

    val repository by lazy {
        val database = Room.databaseBuilder<WatchDatabase>(this, "watcher.db")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        AppRepository(this, database)
    }

    override fun onCreate() {
        super.onCreate()
        repository.schedule()
    }
}

internal val Context.watchRepository: AppRepository
    get() = (applicationContext as FgwApplication).repository
