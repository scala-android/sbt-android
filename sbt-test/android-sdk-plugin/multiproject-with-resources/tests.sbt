import android.Keys._
import Tests._

TaskKey[Unit]("check-lib-for-nothing") <<= (sbt.Keys.`package` in (core, Compile)) map { j =>
  val found = findInArchive(j) (_ == "nothing.txt")
  if (!found) error("nothing.txt not found in library")
}

TaskKey[Unit]("check-for-nothing") <<= (apkFile in Android) map { a =>
  val found = findInArchive(a) (_ == "nothing.txt")
  if (!found) error("nothing.txt not found in APK")
}

TaskKey[Seq[String]]("list-apk") <<= (apkFile in Android) map { a =>
  listArchive(a)
}
