package ro.zapakid.app

import android.app.Application

class ZapaKidApplication : Application() {
    /** Replaced only by emulator tests, before an activity starts. */
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
