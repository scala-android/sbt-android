import sbt._
import sbt.Keys._
import android.Dependencies.LibraryProject
import android.Keys._

object MyProjectBuild extends Build {

  // meta project
  lazy val root = Project(id = "gmroot", base = file(".")).settings(
    android.Plugin.androidCommands :+
    (install <<= ( // install all apps
      install in (guidemate, Android),
      install in (geophon, Android)) map { (_,_) => () }): _* //:+
    ).aggregate(guidemate, geophon, guidemate_lib)


  // android application project
  lazy val guidemate = Project(id = "app", base = file("app")).settings(
   appSettings:_*).dependsOn(guidemate_lib)

  lazy val geophon = Project(id = "app2", base = file("app2")).settings(
    appSettings:_*).dependsOn(guidemate_lib)

  val guidemate_lib = Project(id = "lib", 
    base = file("lib-with-resources")).settings(
    android.Plugin.androidBuildApklib: _*)
    .settings(libraryDependencies ++= Seq(
                        "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test",
                        "com.pivotallabs" % "robolectric" % "1.1" % "test",
                        "junit" % "junit" % "4.10" % "test",
                        "commons-io" % "commons-io" % "2.1",
                        "com.javadocmd" % "simplelatlng" % "1.0.0",
                        "org.joda" % "joda-convert" % "1.2",
                        "joda-time" % "joda-time" % "2.0",
                        "commons-lang" % "commons-lang" % "2.6",
                        "org.osmdroid" % "osmdroid-android" % "3.0.10",
                        "org.slf4j" % "slf4j-simple" % "1.7.5"))

  lazy val appSettings = android.Plugin.androidBuild(guidemate_lib) ++
    List(localProjects in Android += LibraryProject(guidemate_lib.base),
        platformTarget in Android := "android-17")
}
