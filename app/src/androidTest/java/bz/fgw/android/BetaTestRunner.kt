package bz.fgw.android

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.testing.WorkManagerTestInitHelper

/** Keep periodic work under the test driver's control, including during Application startup. */
class BetaTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        super.callApplicationOnCreate(app)
    }
}
