package org.medianexus.music.downloads

import android.app.job.JobScheduler
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** What is asked of Android's job scheduler to carry downloads on, on Android's own classes. */
@RunWith(RobolectricTestRunner::class)
class KeeperTest {
    private lateinit var context: Context
    private lateinit var jobs: JobScheduler

    @Before
    fun start() {
        context = RuntimeEnvironment.getApplication()
        jobs = context.getSystemService(JobScheduler::class.java)
        DownloadKeeper.visible = false
        DownloadKeeper.letGo(context)
    }

    @After
    fun stop() = DownloadKeeper.letGo(context)

    @Test
    fun theJobAsksForANetworkByWhatItIsNotByWhetherAndroidHasValidatedIt() {
        val any = DownloadKeeper.job(context, wifiOnly = false, userInitiated = false).requiredNetwork!!
        assertTrue(any.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        // A home Wi-Fi with a server on it and no internet beyond is never "validated".
        assertFalse(any.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(any.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        // A server reached over a VPN is as good as any.
        assertFalse(any.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        val wifi = DownloadKeeper.job(context, wifiOnly = true, userInitiated = false).requiredNetwork!!
        assertTrue(wifi.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertFalse(wifi.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertTrue(DownloadKeeper.job(context, wifiOnly = true, userInitiated = false).isPersisted)
    }

    @Test
    fun aScheduleAndroidRejectedIsAskedForAgain() {
        // Android won't take the job.
        shadowOf(jobs).failOnJob(DownloadKeeper.JOB_ID)
        DownloadKeeper.update(context, busy = true, wifiOnly = true)
        assertNull(jobs.getPendingJob(DownloadKeeper.JOB_ID))
        // Still not, at the next look.
        DownloadKeeper.update(context, busy = true, wifiOnly = true)
        assertNull(jobs.getPendingJob(DownloadKeeper.JOB_ID))
        // Now it will. Nothing about the downloads has changed (they are only waiting, and
        // say nothing): it is asked for again in a while all the same.
        // (The stand-in scheduler forgets which jobs it was told to refuse only when cleared.)
        shadowOf(jobs).let { shadow -> (shadow.javaClass.getDeclaredField("jobsToFail").apply { isAccessible = true }.get(shadow) as MutableCollection<*>).clear() }
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertNotNull(jobs.getPendingJob(DownloadKeeper.JOB_ID))
    }

    @Test
    fun signedOutTheJobIsLetGoAndAChangeOfNetworkRuleAsksAfresh() {
        DownloadKeeper.update(context, busy = true, wifiOnly = true)
        val first = jobs.getPendingJob(DownloadKeeper.JOB_ID)!!
        assertTrue(first.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        // Wi-Fi only switched off: the job is asked for again, for any network.
        DownloadKeeper.update(context, busy = true, wifiOnly = false)
        assertFalse(jobs.getPendingJob(DownloadKeeper.JOB_ID)!!.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        // Signed out: nothing is carried on.
        DownloadKeeper.letGo(context)
        assertNull(jobs.getPendingJob(DownloadKeeper.JOB_ID))
        assertEquals(0, jobs.allPendingJobs.size)
    }
}
