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
  // automatically set from ANDROID_HOME or *.properties ("sdk.dir")
  val sdkPath = SettingKey[String]("sdk-path",
    "Path to the Android SDK, defaults to ANDROID_HOME or 'sdk.dir' from properties")
  val ndkPath = SettingKey[Option[String]]("ndk-path",
    "Path to the Android NDK, defaults to ANDROID_NDK_HOME or 'ndk.dir' from properties")
  val libraryRequests = SettingKey[Seq[(String,Boolean)]]("library-requests",
    "android library requests, name -> required")
  val platformTarget = SettingKey[String]("platform-target",
    "target API level as described by 'android list targets' (the ID string)")
  val buildToolsVersion = SettingKey[Option[String]]("build-tools-version",
    "Version of Android build-tools to utilize, None (default) for latest")
  val bootClasspath = TaskKey[sbt.Keys.Classpath](
    "boot-classpath", "boot classpath for android platform jar")
  val updateCheck = TaskKey[Unit]("update-check", "Check for a new version of the plugin")

  // layout-related keys
  val projectLayout = SettingKey[ProjectLayout]("project-layout",
    "setting to determine whether the project is laid out ant or gradle-style")
  val outputLayout = SettingKey[ProjectLayout => BuildOutput]("output-layout",
    "setting for defining project build output layout")
  val antLayoutDetector = TaskKey[Unit]("ant-layout-detector",
    "detects and warns if an android project is using an ant-style layout")

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
  val shrinkResources = SettingKey[Boolean]("shrink-resources",
    "automatically remove unused resources from final apk, default false")
  val resourceShrinker = TaskKey[File]("resource-shrinker",
    "Resource shrinking task, dual to shrinkResources")
  val resValues = TaskKey[Seq[(String,String,String)]]("res-values",
    "a sequence of ('type', 'field name', 'value') to inject into res values.xml")
  val resValuesGenerator = TaskKey[Unit]("res-values-generator",
    "generate res-values into values.xml")
  val extraResDirectories = SettingKey[Seq[File]]("extra-res-directories",
    "list of additional android res folders to include (primarily for flavors")
  val extraAssetDirectories = SettingKey[Seq[File]]("extra-asset-directories",
    "list of additional android asset folders to include (primarily for flavors")

  // packaging-related keys
  val packageRelease = TaskKey[File]("package-release", "create a release apk")
  val packageDebug = TaskKey[File]("package-debug", "create a debug apk")
  val packageAar = TaskKey[File]("package-aar", "package aar artifact")
  val packageApklib = TaskKey[File]("package-apklib", "package apklib artifact")
  val apkFile = SettingKey[File]("apk-file",
    "consistent file name for apk output, used for IDE integration")
  val apkSigningConfig = SettingKey[Option[ApkSigningConfig]]("apk-signing-config",
    "signing configuration for release builds")
  val signRelease = TaskKey[File]("sign-release", "sign the release build")
  val zipalign = TaskKey[File]("zipalign", "zipalign the final package")
  val packagingOptions = SettingKey[PackagingOptions]("packaging-options",
    "android packaging options, excludes, firsts and merges")
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
  val localProjects = SettingKey[Seq[LibraryDependency]]("local-projects",
    "local android library projects that need to be built")
  val libraryProjects = TaskKey[Seq[LibraryDependency]]("library-projects",
    "android library projects to reference, must be built separately")
  val libraryProject = SettingKey[Boolean]("library-project",
    "setting indicating whether or not this is a library project")

  // manifest-related keys
  val applicationId = TaskKey[String]("application-id",
    "apk pkg id, is android:packageName if set, otherwise manifest package name")
  @deprecated("Use `applicationId in Android` instead", "1.4.6")
  val packageName = SettingKey[String]("package-name",
    "Deprecated android application ID, use android:application-id instead")
  val manifest = TaskKey[Elem]("manifest",
    "android manifest xml object, read-only, do not modify")
  val processManifest = TaskKey[File]("process-manifest",
    "manifest munging task, if desired, the resulting file can be modified")
  val manifestPlaceholders = TaskKey[Map[String,String]](
    "manifest-placeholders", "${variable} expansion for AndroidManifest.xml")
  val packageForR = TaskKey[String]("packageForR",
    "Custom package name for aapt --custom-package, defaults to manifest package name")
  val versionName = TaskKey[Option[String]]("version-name",
    "application version name")
  val versionCode = TaskKey[Option[Int]]("version-code",
    "application version code")
  val targetSdkVersion = TaskKey[String]("target-sdk-version", "android target")
  val minSdkVersion = TaskKey[String]("min-sdk-version", "android minSdk")
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
  val rsTargetApi = taskKey[String]("renderscript target api, default: minSdkVersion")
  val rsSupportMode = settingKey[Boolean]("renderscript support mode, default: false")
  val rsOptimLevel = settingKey[Int]("renderscript optimization level, default: 3")
  val renderscript = TaskKey[Seq[File]]("renderscript", "android renderscript source-gen task")

  // dex-related keys
  val dexLegacyMode = TaskKey[Boolean]("dex-legacy-mode",
    "disables dex optimizations: predex and sharding, true on minSdkVersion<21")
  val dexShards = SettingKey[Boolean]("dex-shards",
    "whether to enable dex sharding, requires v21+, debug-only")
  val dex = TaskKey[File]("dex", "run bytecode dexer")
  val predex = TaskKey[Seq[(File,File)]]("predex", "pre-dex input libraries task")
  val dexInputs = TaskKey[(Boolean,Seq[File])]("dex-inputs", "incremental dex, input jars to dex")
  val dexMaxHeap = SettingKey[String]("dex-max-heap",
   "Maximum heapsize for dex, default 1024m")
  val dexMulti = SettingKey[Boolean]("dex-multi",
    "multi-dex flag for dex, default false")
  val dexMainClasses = SettingKey[Seq[String]]("dex-main-classes",
    "list of class files that go into main-dex-list parameter for dex")
  val dexMinimizeMain = SettingKey[Boolean]("dex-minimize-main",
    "minimal-main-dex flag for dex, default false")
  val dexMainClassesConfig = TaskKey[File]("dex-main-classes-config",
    "task which produces main-dex-list input file using dex-main-classes as input")
  val dexAdditionalParams = SettingKey[Seq[String]]("dex-additional-params",
    "additional params to pass to dex")

  // proguard-related keys
  val proguardScala = SettingKey[Boolean]("proguard-scala",
    "include scala-library in proguard: true if scala source present")
  val proguardLibraries = TaskKey[Seq[File]]("proguard-libraries",
    "files to pass as proguard -libraryjars")
  val proguardOptions = TaskKey[Seq[String]]("proguard-options",
    "additional options to add to proguard-config")
  val proguardConfig = TaskKey[Seq[String]]("proguard-config",
    "base proguard configuration")
  val proguardCache = SettingKey[Seq[String]]("proguard-cache",
    "list of package names for caching proguard outputs, more rules => more cache misses")
  val proguard = TaskKey[Option[File]]("proguard",
    "proguard task, generates obfuscated.jar")
  val useSdkProguard = SettingKey[Boolean]("use-sdk-proguard",
    "use the sdk proguard config or this plugin's; default = !using scala")
  val useProguard = SettingKey[Boolean]("use-proguard",
    "whether or not to run proguard, automatically true with useProguardInDebug")
  val useProguardInDebug = SettingKey[Boolean]("use-proguard-in-debug",
    "whether or not to run proguard in debug, automatically true with scala")
  val retrolambdaEnabled = SettingKey[Boolean]("retrolambda-enable",
    "enable java8 backport support")

  val allDevices = SettingKey[Boolean]("all-devices",
    "android: execute install, run and test on all connected devices, or just selected")
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

  val Android = config("android")

  implicit def toRichProject(project: Project): RichProject = RichProject(project)

  case class PackagingOptions(excludes: Seq[String] = Nil, pickFirsts: Seq[String] = Nil, merges: Seq[String] = Nil) {
    import collection.JavaConverters._
    def asAndroid = new com.android.builder.model.PackagingOptions {
      override def getPickFirsts = pickFirsts.toSet.asJava
      override def getMerges = merges.toSet.asJava
      override def getExcludes = excludes.toSet.asJava
    }
  }
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
    val builder = TaskKey[AndroidBuilder]("android-builder", "AndroidBuilder object")
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
    val setDebug = TaskKey[Unit]("set-debug", "set debug build")
    val setRelease = TaskKey[Unit]("set-release", "set release build")
    val aars = TaskKey[Seq[LibraryDependency]]("aars",
      "unpack the set of referenced aars")
    val testAggregate = TaskKey[Aggregate.AndroidTest]("test-aggregate",
      "Internal android:test-related key aggregating task")
    val apkbuildAggregate = TaskKey[Aggregate.Apkbuild]("apkbuild-aggregate",
      "Internal apkbuild-related key aggregating task")
    val proguardAggregate = TaskKey[Aggregate.Proguard]("proguard-aggregate",
      "Internal proguard-related key aggregating task")
    val manifestAggregate = TaskKey[Aggregate.Manifest]("manifest-aggregate",
      "Internal manifest-related key aggregating task")
    val retrolambdaAggregate = TaskKey[Aggregate.Retrolambda]("retrolambda-aggregate",
      "Internal retrolambda-related key aggregating task")
    val dexAggregate = TaskKey[Aggregate.Dex]("dex-aggregate",
      "Internal dex-related key aggregating task")

    // alias to ease typing
    val packageT = sbt.Keys.`package`
  }

  def inProjectScope(p: ProjectReference)(ss: Seq[Setting[_]]) =
    inScope(ThisScope.copy(project = Select(p)))(ss)
}
