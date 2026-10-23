package com.example.granter

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlin.concurrent.thread

/**
 * Thay cho dịch vụ foreground: không có tiến trình chạy thường trực, không có thông báo.
 * Hệ thống tự theo dõi khóa MILLET_NO_RESTRICT_APP và chỉ gọi lớp này khi khóa đó đổi.
 */
class GuardJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        thread {
            var again = false
            try {
                again = Gate.ok(ctx) &&
                        License.refreshIfDue(ctx, 5 * 60 * 1000L) == License.State.ACTIVE
                if (again) Guard.fire(ctx, log = true)
            } catch (e: Throwable) {
            } finally {
                // Job theo dõi nội dung chỉ chạy một lần: phải đăng ký lại trước khi kết thúc
                if (again) schedule(ctx)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val JOB_ID = 7101

        /** Theo dõi nội dung (TriggerContentUri) cần Android 7.0 trở lên. */
        fun supported(): Boolean = Build.VERSION.SDK_INT >= 24

        /** Job theo dõi khóa đang được đăng ký trong hệ thống? */
        fun isScheduled(ctx: Context): Boolean = try {
            supported() && js(ctx).getPendingJob(JOB_ID) != null
        } catch (e: Throwable) { false }

        private fun js(ctx: Context) =
            ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        /** true = đã đăng ký thành công. */
        fun schedule(ctx: Context): Boolean {
            if (!supported()) return false
            return try {
                val uri = Settings.System.getUriFor(Guard.KEY)
                val b = JobInfo.Builder(JOB_ID, ComponentName(ctx, GuardJob::class.java))
                    .addTriggerContentUri(
                        JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                // Theo dõi thêm khóa cloud_memory_freeze_whitelist (bảng nào bị ghi đè cũng bắt được)
                for (u in Guard.freezeUris()) {
                    b.addTriggerContentUri(
                        JobInfo.TriggerContentUri(u, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                }
                val info = b
                    .setTriggerContentUpdateDelay(500)
                    .setTriggerContentMaxDelay(3000)
                    .build()
                js(ctx).schedule(info) == JobScheduler.RESULT_SUCCESS
            } catch (e: Throwable) {
                false
            }
        }

        fun cancel(ctx: Context) {
            try { js(ctx).cancel(JOB_ID) } catch (e: Throwable) {}
        }
    }
}
