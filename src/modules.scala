import sbt._

object AndroidModuleID {
  implicit def toAndroidModuleID(m: ModuleID) = AndroidModuleID(m)
}
case class AndroidModuleID(mid: ModuleID) {
  // excludes are temporary until everything/one uses libraryDependencies
  def artifacts(name: String, exttype: String) =
    mid.artifacts(Artifact(name, exttype, exttype)) exclude (
      "com.google.android", "support-v4") exclude (
      "com.google.android", "support-v13")

  def apklib(name: String): ModuleID = artifacts(name, "apklib")
  def aar(name: String): ModuleID    = artifacts(name, "aar")
  def apklib(): ModuleID             = apklib(mid.name)
  def aar(): ModuleID                = aar(mid.name)

}
// vim: set ts=2 sw=2 et:
