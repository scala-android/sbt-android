import android.Keys._

TaskKey[Unit]("verify-package") <<= (packageName in Android) map { p =>
  if (p != "com.example.app") error("wrong package: " + p)
  ()
}
