import android.Keys._

android.Plugin.androidBuild

name := "lib-with-resources"

platformTarget in Android := "android-17"

showSdkProgress in Android := false

javacOptions in Compile ++= List("-source", "1.7", "-target", "1.7")
