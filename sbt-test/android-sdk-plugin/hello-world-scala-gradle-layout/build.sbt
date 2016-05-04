import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-17"

scalaVersion := "2.11.2"

name := "hello-world"

javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6")

showSdkProgress in Android := false
