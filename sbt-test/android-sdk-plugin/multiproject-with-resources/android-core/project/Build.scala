import sbt._
import sbt.Keys._

object CoreBuild extends Build {

  lazy val androidCore = Project(
    id = "android-core",
    base = file("."),
    settings = Defaults.defaultSettings ++ Seq(
      name := "Android Core",
      exportJars := true
    )
  )
}
