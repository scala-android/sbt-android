import android.Keys._
import Tests._

TaskKey[Unit]("check-apk-for-resource") <<= (apkFile in Android) map { a =>
  val found = findInArchive(a) (_ == "res/raw/invert.bc")
  if (!found) error("Renderscript resource not found in APK\n" + listArchive(a))
}

TaskKey[Unit]("check-aar-for-resource") <<= (packageAar in Android) map { a =>
  val found = findInArchive(a) (_ == "res/raw/invert.bc")
  if (!found) error("Renderscript resource not found in Aar\n" + listArchive(a))
}

TaskKey[Seq[String]]("list-apk") <<= (apkFile in Android) map { a =>
  listArchive(a)
}
