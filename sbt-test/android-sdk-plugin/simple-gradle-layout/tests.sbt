import android.Keys._

TaskKey[Unit]("verify-package") <<= (applicationId in Android) map { p =>
  if (p != "com.example.app") error("wrong package: " + p)
  ()
}
