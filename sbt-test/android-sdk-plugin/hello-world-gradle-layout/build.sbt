import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-17"

name := "hello-world"

libraryDependencies += "junit" % "junit" % "4.11" % "test"
