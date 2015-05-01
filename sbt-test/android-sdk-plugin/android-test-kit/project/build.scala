import sbt._
object Build extends android.AutoBuild {
  lazy val root = project.in(file("."))
  lazy val flavor1 = android.Plugin.flavorOf(root, "flavor1")
}
