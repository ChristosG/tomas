package gr.dimitris.app

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import gr.dimitris.app.core.settings.DeviceRole
import gr.dimitris.app.core.settings.Settings
import kotlinx.coroutines.runBlocking

/**
 * Answers the first-run question — «Τίνος είναι αυτό το τηλέφωνο;» — before any test opens a screen.
 *
 * Every instrumented run starts from `pm clear`, so without this the app would open on the role
 * screen and every flow test would be looking at a phone that has not been set up yet. The tests
 * are about what Dimitris and his caregivers do afterwards; the question itself is covered by
 * [gr.dimitris.app.today.RoleScreenTest] and by the manual checks.
 *
 * [Settings] is built on the application context, which is where the app's own settings store
 * lives, so this writes to the same DataStore the graph reads and not to a second one.
 */
class DimitrisTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        runBlocking { Settings(app).setDeviceRole(DeviceRole.DIMITRIS) }
    }
}
