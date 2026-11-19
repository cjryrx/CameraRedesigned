package com.cjryrx.cameraredesigned

import android.app.Application
import com.cjryrx.cameraredesigned.data.AppRepository
import com.cjryrx.cameraredesigned.data.ProcessingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CameraApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository by lazy { AppRepository(this) }
    val processing by lazy { ProcessingQueue(this, scope) }
}
