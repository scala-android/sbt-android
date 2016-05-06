import android.Keys._

android.Plugin.androidBuild

name := "renderscript"

platformTarget in Android := "android-17"

// need to target different versions on 23.+
buildToolsVersion in Android := Some("22.0.1")

showSdkProgress in Android := false
