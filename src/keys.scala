package android

import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.detector.api.Issue
import sbt._

import scala.xml.Elem

import java.io.File
import java.util.Properties

import com.android.builder.core.AndroidBuilder
import com.android.sdklib.{BuildToolInfo, IAndroidTarget, SdkManager}
import com.android.utils.ILogger

import Dependencies._
import com.android.builder.sdk.SdkLoader

import language.implicitConversions

object Keys {
  // alias types that got refactored out
  type ProjectLayout = android.ProjectLayout
  val ProjectLayout = android.ProjectLayout

  type ProguardCache = android.ProguardCache
  val ProguardCache = android.ProguardCache
  type ProguardInputs = android.ProguardInputs
  val ProguardInputs = android.ProguardInputs

  import Dependencies.artifacts
  def apklib(m: ModuleID, n: String): ModuleID = artifacts(m, n, "apklib")
  def apklib(m: ModuleID): ModuleID            = artifacts(m, m.name, "apklib")
  def aar(m: ModuleID, n: String): ModuleID    = artifacts(m, n, "aar")
  def aar(m: ModuleID): ModuleID               = artifacts(m, m.name, "aar")

  val AutoLibraryProject = Dependencies.AutoLibraryProject
  type AutoLibraryProject = Dependencies.AutoLibraryProject

  type LibraryProject = Dependencies.LibraryProject
  val LibraryProject = Dependencies.LibraryProject

  // build-environment keys
  val platformTarget = SettingKey[String]("platform-target",
    "target API level as described by 'android list targets' (the ID string)")
  val buildToolsVersion = SettingKey[Option[String]]("build-tools-version",
    "Version of Android build-tools to utilize, None (default) for latest")

  // layout-related keys
  val projectLayout = SettingKey[ProjectLayout]("project-layout",
    "setting to determine whether the project is laid out ant or gradle-style")
  val binPath = SettingKey[File]("bin-path", "android compiled bin path")
  val genPath = SettingKey[File]("gen-path", "android generated code path")
  val classesJar = SettingKey[File]("classes-jar",
    "generated classes.jar file if in a library project")

  // resource-related keys
  val typedResourcesGenerator = TaskKey[Seq[File]]("typed-resources-generator",
    "TR.scala generating task")
  val typedResources = SettingKey[Boolean]("typed-resources",
    "flag indicating whether to generated TR.scala")
  val typedResourcesIgnores = SettingKey[Seq[String]]("typed-resources-ignores",
    "list of android package names to ignore for TR.scala generation")
  val buildConfigGenerator = TaskKey[Seq[File]]("build-config-generator",
    "generate BuildConfig.java")
  val buildConfigOptions = TaskKey[Seq[(String,String,String)]](
    "build-config-options",
    "a sequence of ('type', 'field name', 'value') to inject into BuildConfig.java")
  val rGenerator = TaskKey[Seq[File]]("r-generator",
    "android aapt source-gen task; generate R.java")
  val collectResources = TaskKey[(File,File)]("collect-resources",
    "copy all resources and assets to a single location for packaging")
  val packageResources = TaskKey[File]("package-resources",
    "package android resources")

  // packaging-related keys
  val packageRelease = TaskKey[File]("package-release", "create a release apk")
  val packageDebug = TaskKey[File]("package-debug", "create a debug apk")
  val packageAar = TaskKey[File]("package-aar", "package aar artifact")
  val packageApklib = TaskKey[File]("package-apklib", "package apklib artifact")
  val apkFile = SettingKey[File]("apk-file",
    "consistent file name for apk output, used for ide integration")
  val apkSigningConfig = SettingKey[Option[ApkSigningConfig]]("apk-signing-config",
    "signing configuration for release builds")
  val signRelease = TaskKey[File]("sign-release", "sign the release build")
  val zipalign = TaskKey[File]("zipalign", "zipalign the final package")
  val apkbuildPickFirsts = SettingKey[Seq[String]]("apkbuild-pickfirsts",
    "filepaths to take first when packing apk, e.g. in case of duplicates")
  val apkbuildExcludes = SettingKey[Seq[String]]("apkbuild-excludes",
    "filepaths to exclude from apk, e.g. in case of duplicates")
  val apkbuild = TaskKey[File]("apkbuild", "generates an apk")
  val apkbuildDebug = SettingKey[MutableSetting[Boolean]]("apkbuild-debug",
    "setting that determines whether to package debug or release, default: debug")

  // testing-related keys
  val instrumentTestTimeout = SettingKey[Int]("instrumentation-test-timeout",
    "Timeout for instrumentation tests, in milliseconds, default is 3 minutes")
  val instrumentTestRunner = SettingKey[String]("instrumentation-test-runner",
    "tests runner, default android.test.InstrumentationTestRunner")
  val debugIncludesTests = SettingKey[Boolean]("debug-includes-tests",
    "Whether instrumentation tests should be included in the debug apk")

  // dependency/library-related keys
  val transitiveAndroidLibs = SettingKey[Boolean]("transitive-android-libs",
    "allow transitive aar and apklib dependencies, default true")
  val transitiveAndroidWarning = SettingKey[Boolean]("transitive-android-warning",
    "warn when transitive android dependencies will be ignored, default true")
  val autolibs = TaskKey[Seq[LibraryDependency]]("autolibs",
    "automatically reference sources in (declared) library projects")
  val apklibs = TaskKey[Seq[LibraryDependency]]("apklibs",
    "unpack the set of referenced apklibs")
  val localAars = SettingKey[Seq[File]]("local-aars", "local aar files")
  val aars = TaskKey[Seq[LibraryDependency]]("aars",
    "unpack the set of referenced aars")
  val localProjects = SettingKey[Seq[LibraryDependency]]("local-projects",
    "local android library projects that need to be built")
  val libraryProjects = TaskKey[Seq[LibraryDependency]]("library-projects",
    "android library projects to reference, must be built separately")
  val libraryProject = SettingKey[Boolean]("library-project",
    "setting indicating whether or not this is a library project")

  // manifest-related keys
  val packageName = SettingKey[String]("package-name",
    "android package name, can be changed to create a different apk package")
  // TODO turn this and all dependents into a TaskKey, manifest can change
  val manifest = SettingKey[Elem]("manifest", "android manifest xml object")
  val manifestPlaceholders = TaskKey[Map[String,String]](
    "manifest-placeholders", "${variable} expansion for AndroidManifest.xml")
  val packageForR = SettingKey[String]("packageForR",
    "Custom package name for aapt --custom-package, defaults to packageName")
  val versionName = SettingKey[Option[String]]("version-name",
    "application version name")
  val versionCode = SettingKey[Option[Int]]("version-code",
    "application version code")
  val targetSdkVersion = SettingKey[String]("target-sdk-version", "android target")
  val minSdkVersion = SettingKey[String]("min-sdk-version", "android minSdk")
  val mergeManifests = SettingKey[Boolean]("merge-manifests",
    "merge manifests from libs, disable if libraries have bad manifests")

  // ndk-related keys
  val collectJni = TaskKey[Seq[File]]("collect-jni",
    "collect all JNI folder names for packaging")
  val collectProjectJni = taskKey[Seq[File]]("collect project JNI folder names for packaging (without libs from dependencies)")
  val ndkJavah = TaskKey[Seq[File]]("ndk-javah",
    "android javah task, generates javah headers from native classes")
  val ndkBuild = TaskKey[Seq[File]]("ndk-build",
    "android ndk-build task, builds all auto-library project's ndk as well")

  // android build task keys; they don't fit in anywhere else
  val aidl = TaskKey[Seq[File]]("aidl", "android aidl source-gen task")

  // renderscript keys
  val rsTargetApi = settingKey[String]("renderscript target api, default: minSdkVersion")
  val rsSupportMode = settingKey[Boolean]("renderscript support mode, default: false")
  val rsOptimLevel = settingKey[Int]("renderscript optimization level, default: 3")
  val rsBinPath = settingKey[File]("renderscript output directory")
  val renderscript = TaskKey[Seq[File]]("renderscript", "android renderscript source-gen task")

  // dex-related keys
  val dex = TaskKey[File]("dex", "run bytecode dexer")
  val dexInputs = TaskKey[(Boolean,Seq[File])]("dex-inputs", "input jars to dex")
  val dexMaxHeap = SettingKey[String]("dex-max-heap",
   "Maximum heapsize for dex, default 1024m")
  val dexMulti = SettingKey[Boolean]("dex-multi",
    "multi-dex flag for dex, default false")
  val dexMainFileClasses = SettingKey[Seq[String]]("dex-main-file-classes",
    "list of class files that go into main-dex-list parameter for dex")
  val dexMinimizeMainFile = SettingKey[Boolean]("dex-minimize-main-file",
    "minimal-main-dex flag for dex, default false")
  val dexMainFileClassesConfig = TaskKey[File]("dex-main-file-classes-list-config",
    "task which produces main-dex-list input file using dex-main-dex-classes as input")
  val dexAdditionalParams = SettingKey[Seq[String]]("dex-additional-params",
    "additional params to pass to dex")

  // proguard-related keys
  val proguardScala = SettingKey[Boolean]("proguard-scala",
    "include scala-library in proguard: true if scala source present")
  val proguardLibraries = SettingKey[Seq[File]]("proguard-libraries",
    "files to pass as proguard -libraryjars")
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
  val retrolambdaEnable = SettingKey[Boolean]("retrolambda-enable",
    "enable java8 backport support")

  val install = TaskKey[Unit]("install", "Install the built app to device")
  val uninstall = TaskKey[Unit]("uninstall", "Remove the app from the device")

  val lint = TaskKey[Unit]("lint", "Run android lint checks independently of compile")
  val lintFlags = SettingKey[LintCliFlags]("lint-flags",
    "flags for running lint, default = quiet")
  val lintStrict = SettingKey[Boolean]("lint-strict",
    "fail the build if true, default: false")
  val lintEnabled = SettingKey[Boolean]("lint-enabled",
    "Whether to enable lint checking during compile, default: true")
  val lintDetectors = SettingKey[Seq[Issue]]("lint-issues",
    "Issues to detect, default: ApiDetectors")

  // alias to ease typing
  val packageT = sbt.Keys.`package`
  val Android = config("android")

  implicit def toRichProject(project: Project): RichProject = RichProject(project)

  // yuck, but mutable project global state is better than build global state
  object MutableSetting {
    def apply[A](value: A) = new MutableSetting(value)
  }

  class MutableSetting[A] private(default: A) {
    private var value = default
    def apply() = value
    def apply(newValue: A) = value = newValue
    override def toString = value.toString
  }

  private[android] object Internal {
    val buildTools = taskKey[BuildToolInfo]("Android build tools")
    val ilogger = SettingKey[Logger => ILogger]("ilogger",
      "internal Android SDK logger")
    val debugTestsGenerator = TaskKey[Seq[File]]("debug-tests-generator",
      "includes test sources in debug builds if debug-includes-tests")
    val zipalignPath = TaskKey[String]("zipalign-path",
      "path to the zipalign executable")
    val builder = TaskKey[AndroidBuilder]("builder", "AndroidBuilder object")
    val apklibArtifact = SettingKey[Artifact]("apklib-artifact",
      "artifact object for publishing apklibs")
    val aarArtifact = SettingKey[Artifact]("aar-artifact",
      "artifact object for publishing aars")
    val cleanForR = TaskKey[Seq[File]]("clean-for-r",
      "Clean all .class files when R.java changes")
    val sdkLoader = TaskKey[SdkLoader]("sdk-loader",
      "Internal android SDK loader")
    val manifestPath = SettingKey[File]("manifest-path",
      "android manifest file path")
    val platform = TaskKey[IAndroidTarget]("platform",
      "IAndroidTarget object representing a target API level")
    val platformJars = TaskKey[(String,Seq[String])]("platform-jars",
      "Path to android.jar and optional jars (e.g. google apis), if any")
    val proguardInputs = TaskKey[ProguardInputs]("proguard-inputs",
      "a tuple specifying -injars and -libraryjars (in that order)")
    val sdkManager = TaskKey[SdkManager]("sdk-manager",
      "Android SdkManager object")
    val properties = SettingKey[Properties]("properties",
      "Properties loaded from the project's .property files")
    // should only be set via local.properties or ANDROID_HOME
    val sdkPath = SettingKey[String]("sdk-path", "Path to the Android SDK")
    val processManifest = TaskKey[File]("process-manifest",
      "manifest munging task")
    val setDebug = TaskKey[Unit]("set-debug", "set debug build")
    val setRelease = TaskKey[Unit]("set-release", "set release build")

  }
}
