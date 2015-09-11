package android

import com.android.sdklib.BuildToolInfo
import sbt._

object BuildOutput {
  // TODO figure out how to make this user-configurable
  implicit class AndroidOutput(val layout: ProjectLayout) extends AnyVal {
    def intermediates = layout.bin / "intermediates"
    def generated = layout.bin / "generated"
    def packaging = intermediates / "packaging"
    def output = layout.bin / "output"
    def testOut = intermediates / "test"
    def generatedSrc = generated / "source"
    def generatedRes = generated / "res"
    def rsBin = intermediates / "renderscript"
    def rsRes = rsBin / "res"
    def rsLib = rsBin / "lib"
    def rsObj = rsBin / "obj"
    def rsDeps = rsBin / "rsDeps"
    def aars = intermediates / "aars"
    def apklibs = intermediates / "apklibs"
    def dex = intermediates / "dex"
    def testDex = testOut / "dex"
    def testResApk = testOut / "resources-test.ap_"
    def testApk = testOut / "instrumentation-test.ap_"
    def predex = intermediates / "predex"
    def classes = intermediates / "classes"
    def testClasses = testOut / "classes"
    def classesJar = intermediates / "classes.jar"
    def mergedRes = intermediates / "res"
    def mergedAssets = intermediates / "assets"
    def proguardOut = intermediates / "proguard"
    def rTxt = generatedSrc / "R.txt"
    def testRTxt = testOut / "R.txt"
    def proguardTxt = proguardOut / "proguard.txt"
    def publicTxt = intermediates / "public.txt"
    def maindexlistTxt = dex / "maindexlist.txt"
    def ndk = intermediates / "ndk"
    def ndkObj = ndk / "obj"
    def ndkBin = ndk / "jni"
    def collectJni = ndk / "collect-jni"
    def manifestProcessing = intermediates / "manifest"
    def processedManifest = manifestProcessing / "AndroidManifest.xml"
    def processedTestManifest = testOut / "TestAndroidManifest.xml"
    def processedManifestReport = manifestProcessing / "merge-report.txt"

    def libraryLintConfig = intermediates / "library-lint.xml"

    def unsignedApk(debug: Boolean, name: String) = {
      output.mkdirs()
      val rel = if (debug) "-debug-unaligned.apk"
      else "-release-unsigned.apk"
      val pkg = name + rel
      output / pkg
    }
    def signedApk(apk: File) =
      output / apk.getName.replace("-unsigned", "-unaligned")

    def alignedApk(apk: File) =
      output / apk.getName.replace("-unaligned", "")

    def resApk(debug: Boolean) = {
      packaging.mkdirs()
      packaging / s"resources-${if (debug) "debug" else "release"}.ap_"
    }
    def outputAarFile(name: String) = {
      output.mkdirs()
      output / (name + ".aar")
    }
    def outputApklibFile(name: String) = {
      output.mkdirs()
      output / (name + ".apklib")
    }
    def integrationApkFile(name: String) = {
      val apkdir = intermediates / "build_integration"
      apkdir.mkdirs()
      apkdir / (name + "-BUILD-INTEGRATION.apk")
    }
  }

  // THIS SUCKS!
  def aarsPath(base: File) = ProjectLayout(base).aars
  def apklibsPath(base: File) = ProjectLayout(base).aars
}

object SdkLayout {
  def googleRepository(sdkPath: String) = "google libraries" at (
    file(sdkPath) / "extras" / "google" / "m2repository").toURI.toString
  def androidRepository(sdkPath: String) = "android libraries" at (
    file(sdkPath) / "extras" / "android" / "m2repository").toURI.toString
  def renderscriptSupportLibFile(t: BuildToolInfo) =
    t.getLocation / "renderscript" / "lib"
  def renderscriptSupportLibs(t: BuildToolInfo) =
    (renderscriptSupportLibFile(t) * "*.jar").get
}
trait ProjectLayout {
  def base: File
  def scalaSource: File
  def javaSource: File
  def sources: File
  def testSources: File
  def testScalaSource: File
  def testJavaSource: File
  def testAssets: File
  def testRes: File
  def resources: File
  def res: File
  def assets: File
  def manifest: File
  def testManifest: File
  def gen: File
  def bin: File
  def libs: File
  def aidl: File
  def jni: File
  def jniLibs: File
  def renderscript: File
  def proguard = base / "proguard-project.txt"
}
object ProjectLayout {
  def apply(base: File, target: Option[File] = None) = {
    if ((base / "AndroidManifest.xml").isFile) {
      if ((base / "src" / "main" / "AndroidManifest.xml").isFile) {
        Plugin.fail(s"Both $base/AndroidManifest.xml and $base/src/main/AndroidManifest.xml exist, unable to determine project layout")
      }
      ProjectLayout.Ant(base)
    } else {
      ProjectLayout.Gradle(base, target getOrElse (base / "target"))
    }
  }
  case class Ant(base: File) extends ProjectLayout {
    override def sources = base / "src"
    override def testSources = base / "tests"
    override def testJavaSource = testSources
    override def testScalaSource = testSources
    override def testRes = testSources / "res"
    override def testAssets = testSources / "assets"
    override def scalaSource = sources
    override def javaSource = sources
    override def res = base / "res"
    override def resources = base / "resources"
    override def assets = base / "assets"
    override def manifest = base / "AndroidManifest.xml"
    override def testManifest = testSources / "AndroidManifest.xml"
    override def gen = BuildOutput.AndroidOutput(this).generatedSrc
    override def bin = base / "bin"
    override def libs = base / "libs"
    override def aidl = sources
    override def jni = base / "jni"
    override def jniLibs = libs
    override def renderscript = sources
  }
  case class Gradle(base: File, target: File) extends ProjectLayout {
    override def manifest = sources / "AndroidManifest.xml"
    override def testSources = base / "src" / "androidTest"
    override def testManifest = testSources / "AndroidManifest.xml"
    override def testJavaSource = testSources / "java"
    override def testScalaSource = testSources / "scala"
    override def testRes = testSources / "res"
    override def testAssets = testSources / "assets"
    override def sources = base / "src" / "main"
    override def jni = sources / "jni"
    override def scalaSource = sources / "scala"
    override def javaSource = sources / "java"
    override def res = sources / "res"
    override def resources = sources / "resources"
    override def assets = sources / "assets"
    override def gen = BuildOutput.AndroidOutput(this).generatedSrc
    override def bin = target / "android"
    // XXX gradle project layouts don't really have a "libs"
    override def libs = sources / "libs"
    override def jniLibs = libs
    override def aidl = sources / "aidl"
    override def renderscript = sources / "rs"
  }
  abstract class Wrapped(val wrapped: ProjectLayout) extends ProjectLayout {
    override def base = wrapped.base
    override def resources = wrapped.resources
    override def testSources = wrapped.testSources
    override def sources = wrapped.sources
    override def javaSource = wrapped.javaSource
    override def libs = wrapped.libs
    override def gen = wrapped.gen
    override def testRes = wrapped.testRes
    override def manifest = wrapped.manifest
    override def scalaSource = wrapped.scalaSource
    override def aidl = wrapped.aidl
    override def bin = wrapped.bin
    override def renderscript = wrapped.renderscript
    override def testScalaSource = wrapped.testScalaSource
    override def testAssets = wrapped.testAssets
    override def jni = wrapped.jni
    override def assets = wrapped.assets
    override def testJavaSource = wrapped.testJavaSource
    override def jniLibs = wrapped.jniLibs
    override def res = wrapped.res
  }
}
