package com.aster.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Aster Application class.
 *
 * Annotated with @HiltAndroidApp to initialize Hilt's dependency injection
 * component hierarchy. Must be declared in AndroidManifest.xml as android:name.
 */
@HiltAndroidApp
class AsterApplication : Application()
