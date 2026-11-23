package cn.dumate.a6studio.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cn.dumate.a6studio.model.AndroidProjectStorage
import cn.dumate.a6studio.runtime.RhinoRunEngine
import cn.dumate.a6studio.ui.StudioState
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Holds the studio state across Activity re-creation.
 *
 * Before this, `StudioState` lived in `remember { }`: rotating the phone, switching dark mode or
 * changing the font size threw away the undo stack, the remembered tokens, a running simulation
 * and the recorder's execution id (so the phone-side script could no longer be stopped).
 */
class StudioViewModel(app: Application) : AndroidViewModel(app) {

    val state: StudioState = StudioState(
        platform = AndroidPlatform(app),
        storage = AndroidProjectStorage(app),
        engineFactory = OkHttp,
        engine = RhinoRunEngine
    ).also { it.init() }

    override fun onCleared() {
        // Releases the coroutine scope and the OkHttp pool; otherwise the old state keeps
        // holding sockets after the last screen is gone.
        state.dispose()
        super.onCleared()
    }
}
