import android.Keys._

android.Plugin.androidBuild

name := "renderscript-support-v8"

platformTarget in Android := "android-21"

// need to target different versions on 23.+
buildToolsVersion in Android := Some("22.0.1")

minSdkVersion := "8"

showSdkProgress in Android := false
