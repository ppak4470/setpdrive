package kr.stepdrive.app

import android.app.Application
import kr.stepdrive.app.di.AppContainer

class StepDriveApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
