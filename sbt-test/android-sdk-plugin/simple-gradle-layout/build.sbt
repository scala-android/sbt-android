import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-17"

TaskKey[Unit]("verify-package") <<= (packageName in Android) map { p =>
  if (p != "com.example.app") error("wrong package: " + p)
  ()
}
