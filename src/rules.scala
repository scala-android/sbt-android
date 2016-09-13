package android

import sbt._

import Keys._
import Keys.Internal._

@deprecated("android.Plugin should no longer be used", "1.7.0")
object Plugin extends PluginFail {

  // android build steps
  // * handle library dependencies (android.library.reference.N)
  // * ndk
  // * aidl
  // * renderscript
  // * BuildConfig.java
  // * aapt
  // * compile
  // * obfuscate
  // * dex
  // * png crunch
  // * package resources
  // * package apk
  // * sign
  // * zipalign

  /**
   * create a new project flavor, build outputs will go in "id/android"
   */
  @deprecated("use android.flavorOf", "1.7.0")
  def flavorOf(p: Project, id: String, settings: Setting[_]*): Project = android.flavorOf(p, id, settings:_*)
  @deprecated("use android.withVariant", "1.7.0")
  def withVariant(project: String,
                  buildType: Option[String] = None,
                  flavor: Option[String] = None): Setting[_] =
    android.withVariant(project, buildType, flavor)

  @deprecated("use android.withVariant", "1.7.0")
  def withVariant(p: ProjectReference,
                  buildType: Option[String],
                  flavor: Option[String]): Setting[_] = android.withVariant(p, buildType, flavor)

  @deprecated("use `enablePlugins(AndroidApp)`", "1.7.0")
  lazy val androidBuild: Seq[Setting[_]] = AndroidProject.projectSettings ++ AndroidApp.projectSettings

  @deprecated("Use `enablePlugins(AndroidApp)`", "1.7.0")
  def buildWith(projects: ProjectReference*): Seq[Setting[_]] = android.buildWith(projects)

  @deprecated("use `enablePlugins(AndroidJar)`", "1.7.0")
  lazy val androidBuildJar: Seq[Setting[_]] = androidBuild ++ buildJar

  @deprecated("use `enablePlugins(AndroidLib)`", "1.7.0")
  lazy val androidBuildAar: Seq[Setting[_]] = androidBuildAar()

  @deprecated("use `enablePlugins(AndroidLib)`", "1.7.0")
  def androidBuildAar(projects: ProjectReference*): Seq[Setting[_]] = {
    androidBuild(projects:_*) ++ buildAar
  }

  @deprecated("Use aar files instead", "gradle compatibility")
  lazy val androidBuildApklib: Seq[Setting[_]] = androidBuildApklib()

  @deprecated("Use aar files instead", "gradle compatibility")
  def androidBuildApklib(projects: ProjectReference*): Seq[Setting[_]] = {
    androidBuild(projects:_*) ++ buildApklib
  }

  @deprecated("Use Project.androidBuildWith(subprojects) instead", "1.3.3")
  private[this] def androidBuild(projects: ProjectReference*): Seq[Setting[_]] =
    Plugin.androidBuild ++ buildWith(projects: _*)

  @deprecated("use android.useSupportVectors", "1.7.0")
  def useSupportVectors = android.useSupportVectors

  @deprecated("use `enablePlugins(AndroidJar)`", "1.7.0")
  def buildJar = AndroidJar.projectSettings

  @deprecated("Use `enablePlugins(AndroidLib)`", "1.7.0")
  def buildAar = AndroidLib.projectSettings

  @deprecated("Stop using apklib", "1.7.0")
  def buildApklib = Seq(libraryProject := true) ++
    addArtifact(apklibArtifact, packageApklib)
}
