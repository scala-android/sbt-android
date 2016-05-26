import sbt._, syntax._
import sbt.Keys._
import android.Dependencies.LibraryProject
import android.Keys._

object MyProjectBuild {

  // meta project
  val root = project.in(file(".")).settings(
    install <<= ( // install all apps
      install in (guidemate, Android),
      install in (geophon, Android)) map { (_,_) => () }
    ).aggregate(guidemate, geophon, guidemate_lib)


  // android application project
  lazy val guidemate = project.in(file("app")).androidBuildWith(guidemate_lib).settings(appSettings:_*)
  lazy val geophon = project.in(file("app2")).androidBuildWith(guidemate_lib).settings(appSettings :_*)

  lazy val guidemate_lib = project.in(file("lib-with-resources")).settings(
    android.Plugin.androidBuildAar: _*)
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

  lazy val appSettings = List(
        showSdkProgress in Android := false,
        platformTarget in Android := "android-17",
        useProguard in Android := true,
        useProguardInDebug in Android := true,
        proguardScala in Android := true,
        proguardOptions in Android += "-dontwarn **",
        packagingOptions in Android := PackagingOptions(excludes = Seq(
          "META-INF/LICENSE.txt",
          "META-INF/NOTICE.txt")))
}
