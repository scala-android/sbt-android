import sbt._
import syntax._
import sbt.Keys._

object Build {

  def commonSettings = Seq(
      organization := "com.optrak",
      scalaVersion := Version.scala,
      scalacOptions ++= Seq(
        "-unchecked",
        "-deprecation",
        "-Xlint",
        "-language:_",
        "-encoding", "UTF-8"
      )/*,
      libraryDependencies ++= Seq(
        Dependency.Compile.shapeless,
        Dependency.Compile.scalazCore
      )
      */
    )

  object Version {
    val scala = "2.10.2"
  }

  object Dependency {

    object Compile {
      val shapeless = "com.chuusai" % "shapeless" % "2.0.0-M1" cross CrossVersion.full
      val scalazCore = "org.scalaz" % "scalaz-core_2.10" % "7.0.3"
    }

    object Test {
      val specs2 = "org.specs2" % "specs2_2.10" % "2.2" % "test"
    }

  }

}

