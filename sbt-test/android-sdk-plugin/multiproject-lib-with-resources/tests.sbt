import android.Keys._
import Tests._

TaskKey[Unit]("check-for-properties") <<= (apkFile in (MyProjectBuild.guidemate,Android)) map { a =>
  val found = findInArchive(a) (_ == "com/example/lib/file.properties")
  if (!found) sys.error("Properties not found in APK")
}

TaskKey[Unit]("check-for-bin") <<= (apkFile in (MyProjectBuild.guidemate,Android)) map { a =>
  val found = findInArchive(a) (_ == "com/example/lib/library.bin")
  if (!found) sys.error("Bin file not found in APK")
}

TaskKey[Seq[String]]("list-apk") <<= (apkFile in (MyProjectBuild.guidemate,Android)) map { a =>
  listArchive(a)
}
