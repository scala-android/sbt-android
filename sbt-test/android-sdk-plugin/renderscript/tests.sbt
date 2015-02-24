import android.Keys._
import Tests._

TaskKey[Unit]("check-target-api-equals-min") := {
  if ((rsTargetApi in Android).value != "17")
    sys.error("Renderscript targetApi: " + (rsTargetApi in Android).value + " not equal to minSdkVersion")
}

TaskKey[Unit]("check-apk-for-resource") <<= (apkFile in Android) map { a =>
  val found = findInArchive(a) (_ == "res/raw/invert.bc")
  if (!found) error("Renderscript resource not found in APK\n" + listArchive(a))
}

TaskKey[Unit]("check-aar-for-resource") <<= (packageAar in Android) map { a =>
  val found = findInArchive(a) (_ == "res/raw/invert.bc")
  if (!found) error("Renderscript resource not found in Aar\n" + listArchive(a))
}

TaskKey[Unit]("check-aar-no-libs") <<= (packageAar in Android) map { a =>
  val found = findInArchive(a) (_.contains("libs"))
  if (found) error("Some library was included in aar\n" + listArchive(a))
}

TaskKey[Seq[String]]("list-apk") <<= (apkFile in Android) map { a =>
  listArchive(a)
}

TaskKey[Seq[String]]("list-apk") <<= (apkFile in Android) map { a =>
  listArchive(a)
}
