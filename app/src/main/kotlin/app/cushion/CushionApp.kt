package app.cushion

import android.app.Application
import app.cushion.data.AppGraph

class CushionApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
