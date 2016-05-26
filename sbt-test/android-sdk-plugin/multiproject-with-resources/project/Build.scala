import sbt._, syntax._
import sbt.Keys._
import android.Keys._

object Build {
  val core = project.in(file("android-core")).settings(
    name := "Android Core",
    scalaVersion := "2.10.2",
    exportJars := true
  )

  lazy val androidMain = project.in(file(".")).settings(android.Plugin.androidBuild ++ Seq(
      libraryDependencies ++= Seq(
        "com.scalatags" % "scalatags_2.10" % "0.2.4"
      ),
      scalaVersion := "2.10.2",
      javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6"),
      showSdkProgress in Android := false
    )
  ) dependsOn core

}
