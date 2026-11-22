package io.github.knightdwell.physicsfeed.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.knightdwell.physicsfeed.MainActivity
import io.github.knightdwell.physicsfeed.R
import io.github.knightdwell.physicsfeed.data.Fetcher
import io.github.knightdwell.physicsfeed.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Fetches new papers in the background about twice a day (when there is internet). */
class FetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        if (!store.hasConfig()) return@withContext Result.success()
        runCatching {
            val (feed, hits) = Fetcher.refresh(store.loadConfig(), store.loadFeed())
            store.saveFeed(feed)
            if (hits.isNotEmpty()) notify(applicationContext, hits.size, hits.take(4).map { it.title })
        }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        private const val CHANNEL = "new-papers"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<FetchWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("fetch", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun notify(ctx: Context, count: Int, titles: List<String>) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New papers", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$count new papers in your topics")
                .setContentText(titles.firstOrNull() ?: "")
                .setStyle(NotificationCompat.InboxStyle().also { s -> titles.forEach { s.addLine(it) } })
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(1, n) }
        }
    }
}
