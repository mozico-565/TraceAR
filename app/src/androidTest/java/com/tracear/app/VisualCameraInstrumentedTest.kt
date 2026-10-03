package com.tracear.app

import android.Manifest
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Emulator camera smoke test; does not measure tracking accuracy on real hardware. */
@RunWith(AndroidJUnit4::class)
class VisualCameraInstrumentedTest {
    @get:Rule val cameraPermission:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private fun texts(root:View):List<TextView> = if(root is ViewGroup)(0 until root.childCount).flatMap{texts(root.getChildAt(it))} else if(root is TextView)listOf(root) else emptyList()
    @Test fun visualModeOpensWithoutArCoreAndSurvivesResetAndResume(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        preferences.edit().putInt("tracking_mode",2).commit()
        try{
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use{scenario->
                fun waitFor(predicate:(MainActivity,List<TextView>)->Boolean):Boolean {
                    repeat(100){var found=false;scenario.onActivity{a->found=predicate(a,texts(a.window.decorView))};if(found)return true;Thread.sleep(100)};return false
                }
                assertTrue("CameraX must bind without an ARCore Session",waitFor{a,views->views.any{it.text==a.getString(R.string.select_surface)}})
                // Give the analysis transform time to arrive, then confirm surface through the same UI control.
                assertTrue("Analysis must accept selection and publish tracking status",waitFor{a,views->
                    views.firstOrNull{it.text==a.getString(R.string.track_surface)&&it.visibility==View.VISIBLE}?.performClick()
                    views.any{it.text.toString().startsWith(a.getString(R.string.visual_tracking))||it.text.toString().startsWith(a.getString(R.string.visual_lost))}
                })
                scenario.onActivity{a->texts(a.window.decorView).first{it.text==a.getString(R.string.lock_artwork)}.performClick()}
                assertTrue(waitFor{a,views->views.any{it.text==a.getString(R.string.unlock_artwork)}})
                scenario.onActivity{a->texts(a.window.decorView).first{it.text==a.getString(R.string.hide)}.performClick()}
                assertTrue(waitFor{a,views->views.any{it.text==a.getString(R.string.show)}})
                scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue(waitFor{a,views->views.any{it.text.toString().startsWith(a.getString(R.string.visual_lost))||it.text.toString().startsWith(a.getString(R.string.locked))}})
                scenario.onActivity{a->texts(a.window.decorView).first{it.text==a.getString(R.string.reset_artwork)}.performClick()}
                assertTrue(waitFor{a,views->views.any{it.text==a.getString(R.string.track_surface)&&it.visibility==View.VISIBLE}&&views.any{it.text==a.getString(R.string.lock_artwork)}})
            }
        }finally{preferences.edit().clear().commit()}
    }
}
