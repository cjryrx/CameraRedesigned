package com.cjryrx.cameraredesigned.integration

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.CameraViewModel
import com.cjryrx.cameraredesigned.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Run separately after resetting only the acceptance package's CAMERA runtime permission/flags. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class CameraPermissionDeviceTest : CameraWorkflowFixture() {
    @Test fun denyCameraThenGrantThroughAppAndResumeLivePreview() {
        assumeTrue("Permission UI checks require an isolated permission reset",
            app.packageName == "com.cjryrx.cameraredesigned.acceptance" &&
                InstrumentationRegistry.getArguments().getString("testPermissionRecovery") == "true")
        assertEquals("Revoke CAMERA before this separate invocation", PackageManager.PERMISSION_DENIED,
            app.checkSelfPermission(Manifest.permission.CAMERA))

        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo
        val flags = service.flags
        service.flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = service
        // A permission dialog can pause the activity before ActivityScenario.launch returns.
        val executor = Executors.newSingleThreadExecutor()
        val launching = executor.submit(Callable { ActivityScenario.launch(MainActivity::class.java) })
        try {
            permissionButton("permission_deny_button")
            scenario = launching.get(20, TimeUnit.SECONDS)
            scenario.onActivity { model = ViewModelProvider(it)[CameraViewModel::class.java] }
            val grantFromApp = bounds("Allow camera", "允许使用相机")
            assertNotNull("The denied screen should also offer app settings", findBounds("App permission settings", "系统权限设置"))
            assertFalse("Camera must remain closed after denial", model.state.value.cameraReady)
            assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.CAMERA))
            tap(grantFromApp)
            permissionButton("permission_allow_foreground_only_button", "permission_allow_button")
            waitFor("start real camera after foreground grant") {
                app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && model.state.value.cameraReady
            }
            livePreview()
        } finally {
            try {
                if (launching.isDone && !launching.isCancelled) launching.get().close()
                else launching.cancel(true)
            } finally {
                executor.shutdownNow()
                service.flags = flags
                automation.serviceInfo = service
            }
        }
    }

    /** Only the permission controller's own buttons are eligible for this cross-window action. */
    private fun permissionButton(vararg ids: String) {
        // Runtime permission dialogs may live in the AOSP or Mainline permission controller.
        // Include a resolvable OEM system handler; package visibility can omit standard handlers.
        val controllers = mutableSetOf("com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller")
        @Suppress("DEPRECATION")
        app.packageManager.queryIntentActivities(Intent("android.content.pm.action.REQUEST_PERMISSIONS"),
            PackageManager.MATCH_SYSTEM_ONLY).mapTo(controllers) { it.activityInfo.packageName }
        val deadline = SystemClock.uptimeMillis() + 18_000
        var lastPackage: CharSequence? = null
        val seen = mutableSetOf<String>()
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            lastPackage = root?.packageName
            if (root != null && root.packageName?.toString() in controllers) {
                val nodes = ArrayDeque(listOf(root))
                while (nodes.isNotEmpty()) {
                    val node = nodes.removeFirst()
                    val id = node.viewIdResourceName?.substringAfterLast('/')
                    if (id != null) seen += id
                    if (id in ids && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::addLast)
                }
            }
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Missing system permission action ${ids.toList()}. Controllers=$controllers foreground=$lastPackage IDs=$seen")
    }
}
