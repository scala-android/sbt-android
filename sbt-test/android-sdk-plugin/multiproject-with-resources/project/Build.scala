import sbt._
import sbt.Keys._
import android.Keys._

object Build extends Build {
  val core = ProjectRef(uri("android-core"), "android-core")

  lazy val androidScala = Project(
    id = "android-main",
    base = file("."),
    settings = Defaults.defaultSettings ++ android.Plugin.androidBuild ++ Seq(
      libraryDependencies ++= Seq(
        "com.scalatags" % "scalatags_2.10" % "0.2.4"
      ), scalaVersion := "2.10.2"
    )
  ) dependsOn core

}
