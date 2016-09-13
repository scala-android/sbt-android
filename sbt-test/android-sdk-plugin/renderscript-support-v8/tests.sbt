import android.Keys._
import Tests._

TaskKey[Unit]("check-target-api") := {
  if ((rsTargetApi in Android).value != "18")
    sys.error("Renderscript targetApi not equal to 18: " + (rsTargetApi in Android).value)
}

TaskKey[Unit]("check-support-mode") := {
  if (!(rsSupportMode in Android).value)
    sys.error("Renderscript support mode was not set from project.properties")
}

TaskKey[Unit]("check-apk-for-resource") <<= (apkFile in Android) map { a =>
  val found = findInArchive(a) (_ == "res/raw/invert.bc")
  if (!found) error("Renderscript resource not found in APK\n" + listArchive(a))
}

//TaskKey[Unit]("check-aar-for-resource") <<= (packageAar in Android) map { a =>
//  val found = findInArchive(a) (_ == "res/raw/invert.bc")
//  if (!found) error("Renderscript resource not found in Aar\n" + listArchive(a))
//}

val jniLibs = Seq( 
  "x86/librs.invert.so",
  "x86/librsjni.so",
  "x86/libRSSupport.so",
  "armeabi-v7a/librs.invert.so",
  "armeabi-v7a/librsjni.so",
  "armeabi-v7a/libRSSupport.so"
)

def checkLibsInArchive(a: File, libs: Seq[String]) = {
  val entries = listArchive(a).toSet
  libs foreach { lib =>
    if (!entries.contains(lib)) error(s"Library: $lib missing in archive: $a")
  }
}

//TaskKey[Unit]("check-aar-for-libs") := { 
//  checkLibsInArchive((packageAar in Android).value, "libs/renderscript-v8.jar" +: (jniLibs.map("jni/" + _))) 
//}

TaskKey[Unit]("check-apk-for-libs") := { 
  checkLibsInArchive((apkFile in Android).value, jniLibs.map("lib/" + _))
}

TaskKey[Seq[String]]("list-apk") <<= (apkFile in Android) map { a =>
  listArchive(a)
}

//TaskKey[Seq[String]]("list-aar") <<= (packageAar in Android) map { a =>
//  listArchive(a)
//}
