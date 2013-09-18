import android.Keys._
import Tests._

TaskKey[Unit]("check-for-properties") <<= (apkFile in Android) map { a =>
  val found = findInArchive(a) (_ == "com/example/lib/file.properties")
  if (!found) error("Properties not found in APK\n" + listArchive(a))
}

TaskKey[Unit]("check-for-bin") <<= (apkFile in Android) map { a =>
  val found = findInArchive(a) (_ == "com/example/lib/library.bin")
  if (!found) error("Bin file not found in APK")
}

TaskKey[Seq[String]]("list-apk") <<= (apkFile in Android) map { a =>
  listArchive(a)
}
