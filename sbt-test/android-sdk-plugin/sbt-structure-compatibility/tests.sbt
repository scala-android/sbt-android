TaskKey[Unit]("check-structure") := {
  import scala.xml.XML
  val root = XML.loadFile(baseDirectory.value / "structure.xml")
  val android = root \\ "android"
  val andy = "android" -> android
  val vers = "version" -> android \ "version"
  val manif = "manifest" -> android \ "manifest"
  val reso = "resources" -> android \ "resources"
  val assets = "assets" -> android \ "assets"
  val generated = "generated" -> android \ "generatedFiles"
  val natives = "native" -> android \ "nativeLibs"
  val apk = "apk" -> android \ "apk"
  val isLib = "isLib" -> android \ "isLibrary"
  val prog = "proguard" -> android \ "proguard"
  val checks = List(andy, vers, manif, reso, assets, generated, natives, apk, isLib, prog)
  val empty = checks.collect { case x if x._2.isEmpty => x._1 }
  if (empty.nonEmpty)
    sys.error(s"Missing '${empty.mkString(",")}' nodes in structure")
}
