package android

import sbt._
import sbt.Keys._

import scala.xml.Elem

import java.io.File
import java.util.Properties

import com.android.builder.AndroidBuilder
import com.android.builder.SdkParser
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}
import com.android.sdklib.{IAndroidTarget,SdkManager}
import com.android.utils.ILogger

import Dependencies._

object Keys {
  val ilogger = SettingKey[ILogger]("ilogger", "internal Android SDK logger")
  val sdkParser = SettingKey[SdkParser]("sdk-parser",
    "internal Android SdkParser object")
  val typedResourcesGenerator = TaskKey[Seq[File]]("typed-resources-generator",
    "TR.scala generating task")
  val typedResources = SettingKey[Boolean]("typed-resources",
    "flag indicating whether to generated TR.scala")
  val proguardScala = SettingKey[Boolean]("proguard-scala",
    "include scala-library in proguard: true if scala source present")
  val proguardExcludes = SettingKey[Seq[String]]("proguard-excludes",
    "file names to exclude from proguard's -injars")
  val proguardLibraries = SettingKey[Seq[File]]("proguard-libraries",
    "files to pass as proguard -libraryjars")
  val proguardInputs = TaskKey[(Seq[File],Seq[File])]("proguard-inputs",
    "a tuple specifying -injars and -libraryjars (in that order)")
  val setDebug = TaskKey[Unit]("set-debug", "set debug build")
  val signRelease = TaskKey[File]("sign-release", "sign the release build")
  val zipalignPath = SettingKey[String]("zipalign-path",
    "path to the zipalign executable")
  val apklibs = TaskKey[Seq[LibraryDependency]]("apklibs",
    "unpack the set of referenced apklibs")
  val aars = TaskKey[Seq[LibraryDependency]]("aars",
    "unpack the set of referenced aars")
  val zipalign = TaskKey[File]("zipalign", "zipalign the final package")
  val setRelease = TaskKey[Unit]("set-release", "set release build")
  val packageName = SettingKey[String]("package-name", "android package name")
  val apkbuild = TaskKey[File]("apkbuild", "generates an apk")
  val builder = SettingKey[AndroidBuilder]("builder", "AndroidBuilder object")
  val packageRelease = TaskKey[File]("package-release", "create a release apk")
  val packageDebug = TaskKey[File]("package-debug", "create a debug apk")
  val collectResources = TaskKey[(File,File)]("collect-resources",
    "copy all resources and assets to a single location for packaging")
  val packageResources = TaskKey[File]("package-resources",
    "package android resources")
  val packageAar = TaskKey[File]("package-aar", "package aar artifact")
  val packageApklib = TaskKey[File]("package-apklib", "package apklib artifact")
  val apklibArtifact = SettingKey[Artifact]("apklib-artifact",
    "artifact object for publishing apklibs")
  val aarArtifact = SettingKey[Artifact]("aar-artifact",
    "artifact object for publishing aars")
  val packageForR = SettingKey[Option[String]]("packageForR",
    "Custom package name for aapt --custom-package")
  val manifestPath = SettingKey[File]("manifest-path",
    "android manifest file path")
  val targetSdkVersion = SettingKey[Int]("target-sdk-version", "android target")
  // TODO turn this and all dependents into a TaskKey, manifest can change
  val manifest = SettingKey[Elem]("manifest", "android manifest xml object")
  val classesJar = SettingKey[File]("classes-jar",
    "generated classes.jar file if in a library project")
  val libraryProjects = TaskKey[Seq[LibraryDependency]]("library-projects",
    "android library projects to reference, must be built separately")
  val libraryProject = SettingKey[Boolean]("library-project",
    "setting indicating whether or not this is a library project")
  val binPath = SettingKey[File]("bin-path", "android compiled bin path")
  val genPath = SettingKey[File]("gen-path", "android generated code path")
  val properties = SettingKey[Properties]("properties",
    "Properties loaded from the project's .property files")
  val sdkPath = SettingKey[String]("sdk-path", "Path to the Android SDK")
  val sdkManager = SettingKey[SdkManager]("sdk-manager",
    "Android SdkManager object")
  val platformTarget = SettingKey[String]("platform-target",
    "target API level as described by 'android list targets' (the ID string)")
  val platform = SettingKey[IAndroidTarget]("platform",
    "IAndroidTarget object representing a target API level")
  val platformJars = SettingKey[(String,Seq[String])]("platform-jars",
    "Path to android.jar and optional jars (e.g. google apis), if any")
  val buildConfigGenerator = TaskKey[Seq[File]]("build-config-generator",
    "generate BuildConfig.java")
  val rGenerator = TaskKey[Seq[File]]("r-generator",
    "android aapt source-gen task; generate R.java")
  val aidl = TaskKey[Seq[File]]("aidl", "android aidl source-gen task")
  val renderscript = TaskKey[Seq[File]]("renderscript",
    "android renderscript source-gen task")
  val dex = TaskKey[File]("dex", "run bytecode dexer")
  val dexInputs = TaskKey[Seq[File]]("dex-inputs", "input jars to dex")
  val classesDex = SettingKey[File]("classes-dex", "output classes.dex path")
  val versionName = SettingKey[Option[String]]("version-name",
    "application version name")
  val versionCode = SettingKey[Option[String]]("version-code",
    "application version code")
  val proguardOptions = TaskKey[Seq[String]]("proguard-options",
    "additional options to add to proguard-config")
  val proguardConfig = TaskKey[Seq[String]]("proguard-config",
    "base proguard configuration")
  val proguard = TaskKey[Option[File]]("proguard",
    "proguard task, generates obfuscated.jar")
  val useSdkProguard = SettingKey[Boolean]("use-sdk-proguard",
    "use the sdk proguard config or this plugin's; default = !using scala")
  val useProguard = SettingKey[Boolean]("use-proguard",
    "whether or not to run proguard, automatically true with scala")
  val useProguardInDebug = SettingKey[Boolean]("use-proguard-in-debug",
    "whether or not to run proguard in debug, automatically true with scala")
  val install = TaskKey[Unit]("install", "Install the built app to device")
  val uninstall = TaskKey[Unit]("uninstall", "Remove the app from the device")
  val cleanForR = TaskKey[Seq[File]]("clean-for-r",
    "Clean all .class files when R.java changes")

  // alias to ease typing
  val packageT = sbt.Keys.`package`
  val Android = config("android")

}
