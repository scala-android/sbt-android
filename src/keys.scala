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
  val ilogger = SettingKey[Logger => ILogger]("ilogger",
    "internal Android SDK logger")
  val sdkParser = TaskKey[SdkParser]("sdk-parser",
    "internal Android SdkParser object")
  val typedResourcesGenerator = TaskKey[Seq[File]]("typed-resources-generator",
    "TR.scala generating task")
  val projectLayout = SettingKey[ProjectLayout]("project-layout",
    "setting to determine whether the project is laid out ant or gradle-style")
  val typedResources = SettingKey[Boolean]("typed-resources",
    "flag indicating whether to generated TR.scala")
  val proguardScala = SettingKey[Boolean]("proguard-scala",
    "include scala-library in proguard: true if scala source present")
  val proguardLibraries = SettingKey[Seq[File]]("proguard-libraries",
    "files to pass as proguard -libraryjars")
  val proguardInputs = TaskKey[ProguardInputs]("proguard-inputs",
    "a tuple specifying -injars and -libraryjars (in that order)")
  val setDebug = TaskKey[Unit]("set-debug", "set debug build")
  val apklibs = TaskKey[Seq[LibraryDependency]]("apklibs",
    "unpack the set of referenced apklibs")
  val localAars = SettingKey[Seq[File]]("local-aars", "local aar files")
  val aars = TaskKey[Seq[LibraryDependency]]("aars",
    "unpack the set of referenced aars")
  val setRelease = TaskKey[Unit]("set-release", "set release build")
  val packageName = SettingKey[String]("package-name",
    "android package name, can be changed to create a different apk package")
  val apkFile = SettingKey[File]("apk-file",
    "consistent file name for apk output, used for ide integration")
  val signRelease = TaskKey[File]("sign-release", "sign the release build")
  val zipalignPath = SettingKey[String]("zipalign-path",
    "path to the zipalign executable")
  val zipalign = TaskKey[File]("zipalign", "zipalign the final package")
  val apkbuild = TaskKey[File]("apkbuild", "generates an apk")
  val builder = TaskKey[AndroidBuilder]("builder", "AndroidBuilder object")
  val packageRelease = TaskKey[File]("package-release", "create a release apk")
  val packageDebug = TaskKey[File]("package-debug", "create a debug apk")
  val collectJni = TaskKey[File]("collect-jni",
    "copy all NDK libraries to a single location for packaging")
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
  val packageForR = SettingKey[String]("packageForR",
    "Custom package name for aapt --custom-package, defaults to packageName")
  val manifestPath = SettingKey[File]("manifest-path",
    "android manifest file path")
  val targetSdkVersion = SettingKey[Int]("target-sdk-version", "android target")
  val minSdkVersion = SettingKey[Int]("min-sdk-version", "android minSdk")
  val processManifest = TaskKey[File]("process-manifest",
    "manifest munging task")
  // TODO turn this and all dependents into a TaskKey, manifest can change
  val manifest = SettingKey[Elem]("manifest", "android manifest xml object")
  val classesJar = SettingKey[File]("classes-jar",
    "generated classes.jar file if in a library project")
  val localProjects = SettingKey[Seq[LibraryDependency]]("local-projects",
    "local android library projects that need to be built")
  val libraryProjects = TaskKey[Seq[LibraryDependency]]("library-projects",
    "android library projects to reference, must be built separately")
  val libraryProject = SettingKey[Boolean]("library-project",
    "setting indicating whether or not this is a library project")
  val binPath = SettingKey[File]("bin-path", "android compiled bin path")
  val genPath = SettingKey[File]("gen-path", "android generated code path")
  val properties = SettingKey[Properties]("properties",
    "Properties loaded from the project's .property files")
  val sdkPath = SettingKey[String]("sdk-path", "Path to the Android SDK")
  val sdkManager = TaskKey[SdkManager]("sdk-manager",
    "Android SdkManager object")
  val platformTarget = SettingKey[String]("platform-target",
    "target API level as described by 'android list targets' (the ID string)")
  val platform = TaskKey[IAndroidTarget]("platform",
    "IAndroidTarget object representing a target API level")
  val platformJars = TaskKey[(String,Seq[String])]("platform-jars",
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
  val versionCode = SettingKey[Option[Int]]("version-code",
    "application version code")
  val proguardOptions = TaskKey[Seq[String]]("proguard-options",
    "additional options to add to proguard-config")
  val proguardConfig = TaskKey[Seq[String]]("proguard-config",
    "base proguard configuration")
  val proguardCache = SettingKey[Seq[ProguardCache]]("proguard-cache",
    "rules for caching proguard outputs, more rules => more cache misses")
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

  object ProguardCache{
    def apply(prefixes: String*): ProguardCache = {
      if (prefixes.isEmpty) sys.error("ProguardCache prefixes may not be empty")
      val prefixesWithSlashes = prefixes map { prefix =>
        val p = prefix.replaceAll("""\.""", "/")
        if (!p.endsWith("/")) p + "/" else p
      }
      ProguardCache(prefixesWithSlashes, None, None, None, None)
    }
    def apply(prefix: String, org: String): ProguardCache =
      apply(prefix).copy(moduleOrg = Some(org))
    def apply(prefix: String, org: String, name: String): ProguardCache =
      apply(prefix).copy(moduleOrg = Some(org), moduleName = Some(name))
    def apply(prefix: String, file: File): ProguardCache =
      apply(prefix).copy(jarFile = some(file))
  }
  case class ProguardCache ( packagePrefixes: Seq[String]
                           , moduleOrg: Option[String]
                           , moduleName: Option[String]
                           , cross: Option[CrossVersion]
                           , jarFile: Option[File] ) {

    def %(orgOrName: String) = if (moduleOrg.isEmpty) {
      copy(moduleOrg = Some(orgOrName))
    } else {
      copy(moduleName = Some(orgOrName))
    }

    def %%(name: String) = {
      copy(moduleName = Some(name), cross = Some(CrossVersion.binary))
    }

    def <<(file: File) = copy(jarFile = Some(file))

    def matches(classFile: String) = packagePrefixes exists {
      classFile.startsWith(_) && classFile.endsWith(".class")
    }
    def matches(file: File) = jarFile map {
      _.getCanonicalFile == file.getCanonicalFile } getOrElse false

    def matches(file: Attributed[File], state: State): Boolean = {
      matches(file.data) || (file.get(moduleID.key) exists (matches(_, state)))
    }

    def matches(module: ModuleID, state: State): Boolean = {
      (moduleOrg,moduleName,cross) match {
        case (Some(org),Some(name),Some(cross)) =>
          val extracted = Project.extract(state)
          val scalaVersion = extracted.get(sbt.Keys.scalaVersion)
          val scalaBinaryVersion = extracted.get(sbt.Keys.scalaBinaryVersion)
          val cv = CrossVersion(cross, scalaVersion, scalaBinaryVersion)
          val crossName = CrossVersion.applyCross(name, cv)
          org == module.organization &&
            (name == module.name || crossName == module.name)
        case (Some(org),Some(name),None) =>
          org == module.organization && name == module.name
        case (Some(org),None,None) => org == module.organization
        case _ => false
      }
    }
  }

  case class ProguardInputs(//injars: Seq[Attributed[File]],
    injars: Seq[Attributed[File]],
    libraryjars: Seq[File],
    proguardCache: Option[File] = None)

  // alias to ease typing
  val packageT = sbt.Keys.`package`
  val Android = config("android")

  sealed trait ProjectLayout {
    def base: File
    def scalaSource: File
    def javaSource: File
    def sources: File
    def testSources: File
    def testScalaSource: File
    def testJavaSource: File
    def res: File
    def assets: File
    def manifest: File
    def gen: File
    def bin: File
    def libs: File
    def aidl: File
    def jni: File
    def renderscript: File
  }
  object ProjectLayout {
    def apply(base: File) = {
      if ((base / "src" / "main" / "AndroidManifest.xml").isFile)
        ProjectLayout.Gradle(base)
      else
        ProjectLayout.Ant(base)
    }
    case class Ant(base: File) extends ProjectLayout {
      override def sources = base / "src"
      override def testSources = base / "tests"
      override def testJavaSource = testSources
      override def testScalaSource = testSources
      override def scalaSource = sources
      override def javaSource = sources
      override def res = base / "res"
      override def assets = base / "assets"
      override def manifest = base / "AndroidManifest.xml"
      override def gen = base / "gen"
      override def bin = base / "bin"
      override def libs = base / "libs"
      override def aidl = sources
      override def jni = libs
      override def renderscript = sources
    }
    case class Gradle(base: File) extends ProjectLayout {
      override def manifest = sources / "AndroidManifest.xml"
      override def testSources = base / "src" / "test"
      override def testJavaSource = testSources / "java"
      override def testScalaSource = testSources / "scala"
      override def sources = base / "src" / "main"
      override def jni = sources / "jni"
      override def scalaSource = sources / "scala"
      override def javaSource = sources / "java"
      override def res = sources / "res"
      override def assets = sources / "assets"
      override def gen = base / "target" / "android-gen"
      override def bin = base / "target" / "android-bin"
      // XXX gradle project layouts don't really have a "libs"
      override def libs = sources / "libs"
      override def aidl = sources / "aidl"
      override def renderscript = sources / "rs"
    }
  }
}
