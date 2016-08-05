import android.Keys._

android.Plugin.androidBuild

name := "renderscript"

platformTarget in Android := "android-17"

// need to target different versions on 23.+
buildToolsVersion in Android := Some("24.0.0")

showSdkProgress in Android := false
