import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-17"

name := "hello-world"

libraryDependencies += "com.android.support" % "support-v4" % "18.0.0"

proguardCache in Android += ProguardCache("android.support") %
  "com.android.support" % "support-v4"
