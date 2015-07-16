import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-17"

libraryDependencies += "com.google.android.gms" % "play-services" % "4.3.23"

resValues in Android += ("string", "test_resource", "test value")
