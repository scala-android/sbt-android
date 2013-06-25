import sbt._
import sbt.Keys._

import scala.collection.JavaConversions._
import scala.xml.Elem
import scala.xml.XML

import java.io.File
import java.util.Properties

import com.android.builder.AndroidBuilder
import com.android.builder.dependency.JarDependency
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}
import com.android.sdklib.{IAndroidTarget,SdkManager}
import com.android.utils.ILogger

object AndroidKeys {
  val ilogger = SettingKey[ILogger]("ilogger", "internal Android SDK logger")
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
  val zipalign = TaskKey[File]("zipalign", "zipalign the final package")
  val setRelease = TaskKey[Unit]("set-release", "set release build")
  val pngCrunch = TaskKey[Unit]("png-crunch", "optimize png files")
  val packageName = SettingKey[String]("package-name", "android package name")
  val apkbuild = TaskKey[File]("apkbuild", "generates an apk")
  val builder = SettingKey[AndroidBuilder]("builder", "AndroidBuilder object")
  val packageRelease = TaskKey[File]("package-release", "create a release apk")
  val packageDebug = TaskKey[File]("package-debug", "create a debug apk")
  val collectResources = TaskKey[(File,File)]("collect-resources",
    "copy all resources and assets to a single location for packaging")
  val packageResources = TaskKey[File]("package-resources",
    "package android resources")
  val customPackage = SettingKey[Option[String]]("custom-package",
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
    "flag indicating whether or not a library project")
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
  val annotationsJar = SettingKey[String]("annotations-jar",
    "Path to sdk annotations.jar")
  val aaptPath = SettingKey[String]("aapt-path", "path to aapt")
  val aaptNonConstantId = SettingKey[Boolean]("aapt-non-constant-id",
    "generate lib-project R.java files with --non-constant-id, default true")
  val buildConfigGenerator = TaskKey[Seq[File]]("build-config-generator",
    "generate BuildConfig.java")
  val aaptGenerator = TaskKey[Seq[File]]("aapt-generator",
    "android aapt source-gen task")
  val aidl = TaskKey[Seq[File]]("aidl", "android aidl source-gen task")
  val renderscript = TaskKey[Seq[File]]("renderscript",
    "android renderscript source-gen task")
  val dexPath = SettingKey[String]("dex-path", "path to dex")
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
  val packageT = Keys.`package`
  val Android = config("android")

  class LibraryDependency(val path: File) extends AndroidLibrary {
    import com.android.SdkConstants._
    private val manifest = Seq(path / FN_ANDROID_MANIFEST_XML,
      path / "src" / "main" / FN_ANDROID_MANIFEST_XML) collect {
      case f if f.exists => f
    } head

    val pkg = XML.loadFile(manifest).attribute("package").get(0).text

    val binPath = AndroidTasks.directoriesList(
      "out.dir", "bin", AndroidTasks.loadProperties(path), path)(0)

    val libPath = Seq("libs", "lib") map { path / _ } find {
      _.exists } getOrElse (path / "libs")

    override def getManifest = manifest
    override def getFolder = path
    override def getJarFile = path / FN_CLASSES_JAR
    override def getLocalJars = (path / LIBS_FOLDER) ** ".jar" get
    override def getResFolder = path / FD_RES
    override def getAssetsFolder = path / FD_ASSETS
    override def getJniFolder = path / "jni"
    override def getSymbolFile = path / "R.txt"
    override def getAidlFolder = path / FD_AIDL
    override def getRenderscriptFolder = path / FD_RENDERSCRIPT
    override def getLintJar = path / "lint.jar"
    override def getProguardRules = path / "proguard.txt"

    override def getLibraryDependencies = Seq.empty[LibraryDependency]
    override def getDependencies = Seq.empty[AndroidLibrary]
    override def getManifestDependencies = Seq.empty[AndroidLibrary]

    override def getLocalDependencies = getLocalJars map {
      j => new JarDependency(j)
    }
  }

  case class ApkLibrary(override val path: File)
  extends LibraryDependency(path) {
    import com.android.SdkConstants._
    override def getSymbolFile = path / "gen" / "R.txt"
    override def getJarFile = path / "bin" / FN_CLASSES_JAR
  }
  case class AarLibrary(override val path: File) extends LibraryDependency(path)
  case class LibraryProject(override val path: File)
  extends LibraryDependency(path) {
    import com.android.SdkConstants._
    override def getSymbolFile = path / "gen" / "R.txt"
    override def getJarFile = path / "bin" / FN_CLASSES_JAR
    override def getProguardRules = path / "bin" / "proguard.txt"
  }
}
