package android

import java.io.File

import com.android.prefs.AndroidLocation
import com.android.sdklib.BuildToolInfo
import sbt._

import scala.util.Try

trait BuildOutput extends Any {
  def layout: ProjectLayout
  def intermediates: File
  def generated: File
  def packaging: File
  def output: File
  def testOut: File
  def generatedSrc: File
  def generatedRes: File
  def generatedVectors: File
  def rsBin: File
  def rsRes: File
  def rsLib: File
  def rsObj: File
  def rsDeps: File
  def aars: File
  def apklibs: File
  def dex: File
  def testDex: File
  def testResApk: File
  def testApk: File
  def predex: File
  def classes: File
  def testClasses: File
  def classesJar: File
  def mergedRes: File
  def mergedAssets: File
  def mergeBlame: File
  def proguardOut: File
  def rTxt: File
  def testRTxt: File
  def proguardTxt: File
  def publicTxt: File
  def maindexlistTxt: File
  def maindexRootsJar: File
  def ndk: File
  def ndkObj: File
  def ndkBin: File
  def collectJni: File
  def collectResource: File
  def manifestProcessing: File
  def processedManifest: File
  def processedTestManifest: File
  def processedManifestReport: File

  def libraryLintConfig: File

  def unsignedApk(debug: Boolean, name: String): File
  def signedApk(apk: File): File

  def alignedApk(apk: File): File

  def resApk(debug: Boolean): File
  def outputAarFile(name: String): File
  def outputApklibFile(name: String): File
  def integrationApkFile(name: String): File
}
object BuildOutput {
  type Converter = ProjectLayout => BuildOutput

  class AndroidOutput(val layout: ProjectLayout) extends AnyVal with BuildOutput {
    def intermediates = layout.bin / "intermediates"
    def generated = layout.bin / "generated"
    def packaging = intermediates / "packaging"
    def output = layout.bin / "output"
    def testOut = intermediates / "test"
    def generatedSrc = generated / "source"
    def generatedRes = generated / "res"
    def generatedVectors = generated / "vector-drawables"
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
    def mergeBlame = intermediates / "blame"
    def proguardOut = intermediates / "proguard"
    def rTxt = generatedSrc / "R.txt"
    def testRTxt = testOut / "R.txt"
    def proguardTxt = proguardOut / "proguard.txt"
    def publicTxt = intermediates / "public.txt"
    def maindexlistTxt = dex / "maindexlist.txt"
    def maindexRootsJar = dex / "mainDexRoots.jar"
    def ndk = intermediates / "ndk"
    def ndkObj = ndk / "obj"
    def ndkBin = ndk / "jni"
    def collectJni = ndk / "collect-jni"
    def collectResource = intermediates / "resources"
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
      apkdir / (name + "-BUILD-INTEGRATION.apk")
    }
  }
  class Wrapped(val base: BuildOutput) extends BuildOutput {
    def layout = base.layout
    def intermediates = base.intermediates
    def generated = base.generated
    def packaging = base.packaging
    def output = base.output
    def testOut = base.testOut
    def generatedSrc = base.generatedSrc
    def generatedRes = base.generatedRes
    def generatedVectors = base.generatedVectors
    def rsBin = base.rsBin
    def rsRes = base.rsRes
    def rsLib = base.rsLib
    def rsObj = base.rsObj
    def rsDeps = base.rsDeps
    def aars = base.aars
    def apklibs = base.apklibs
    def dex = base.dex
    def testDex = base.testDex
    def testResApk = base.testResApk
    def testApk = base.testApk
    def predex = base.predex
    def classes = base.classes
    def testClasses = base.testClasses
    def classesJar = base.classesJar
    def mergedRes = base.mergedRes
    def mergedAssets = base.mergedAssets
    def mergeBlame = base.mergeBlame
    def proguardOut = base.proguardOut
    def rTxt = base.rTxt
    def testRTxt = base.testRTxt
    def proguardTxt = base.proguardTxt
    def publicTxt = base.publicTxt
    def maindexlistTxt = base.maindexlistTxt
    def maindexRootsJar = base.maindexRootsJar
    def ndk = base.ndk
    def ndkObj = base.ndkObj
    def ndkBin = base.ndkBin
    def collectJni = base.collectJni
    def collectResource = base.collectResource
    def manifestProcessing = base.manifestProcessing
    def processedManifest = base.processedManifest
    def processedTestManifest = base.processedTestManifest
    def processedManifestReport = base.processedManifestReport

    def libraryLintConfig = base.libraryLintConfig

    def unsignedApk(debug: Boolean, name: String) = base.unsignedApk(debug,name)
    def signedApk(apk: File) = base.signedApk(apk)

    def alignedApk(apk: File) = base.alignedApk(apk)

    def resApk(debug: Boolean) = base.resApk(debug)
    def outputAarFile(name: String) = base.outputAarFile(name)
    def outputApklibFile(name: String) = base.outputApklibFile(name)
    def integrationApkFile(name: String) = base.integrationApkFile(name)
  }
  implicit class LayoutOutputExtension(val layout: ProjectLayout)(implicit val base: BuildOutput.Converter) extends BuildOutput {
    def intermediates = base(layout).intermediates
    def generated = base(layout).generated
    def packaging = base(layout).packaging
    def output = base(layout).output
    def testOut = base(layout).testOut
    def generatedSrc = base(layout).generatedSrc
    def generatedRes = base(layout).generatedRes
    def generatedVectors = base(layout).generatedVectors
    def rsBin = base(layout).rsBin
    def rsRes = base(layout).rsRes
    def rsLib = base(layout).rsLib
    def rsObj = base(layout).rsObj
    def rsDeps = base(layout).rsDeps
    def aars = base(layout).aars
    def apklibs = base(layout).apklibs
    def dex = base(layout).dex
    def testDex = base(layout).testDex
    def testResApk = base(layout).testResApk
    def testApk = base(layout).testApk
    def predex = base(layout).predex
    def classes = base(layout).classes
    def testClasses = base(layout).testClasses
    def classesJar = base(layout).classesJar
    def mergedRes = base(layout).mergedRes
    def mergedAssets = base(layout).mergedAssets
    def mergeBlame = base(layout).mergeBlame
    def proguardOut = base(layout).proguardOut
    def rTxt = base(layout).rTxt
    def testRTxt = base(layout).testRTxt
    def proguardTxt = base(layout).proguardTxt
    def publicTxt = base(layout).publicTxt
    def maindexlistTxt = base(layout).maindexlistTxt
    def maindexRootsJar = base(layout).maindexRootsJar
    def ndk = base(layout).ndk
    def ndkObj = base(layout).ndkObj
    def ndkBin = base(layout).ndkBin
    def collectJni = base(layout).collectJni
    def collectResource = base(layout).collectResource
    def manifestProcessing = base(layout).manifestProcessing
    def processedManifest = base(layout).processedManifest
    def processedTestManifest = base(layout).processedTestManifest
    def processedManifestReport = base(layout).processedManifestReport

    def libraryLintConfig = base(layout).libraryLintConfig

    def unsignedApk(debug: Boolean, name: String) = base(layout).unsignedApk(debug,name)
    def signedApk(apk: File) = base(layout).signedApk(apk)

    def alignedApk(apk: File) = base(layout).alignedApk(apk)

    def resApk(debug: Boolean) = base(layout).resApk(debug)
    def outputAarFile(name: String) = base(layout).outputAarFile(name)
    def outputApklibFile(name: String) = base(layout).outputApklibFile(name)
    def integrationApkFile(name: String) = base(layout).integrationApkFile(name)
  }

  // THIS SUCKS!
  def aarsPath(base: File) = new AndroidOutput(ProjectLayout(base)).aars
  def apklibsPath(base: File) = new AndroidOutput(ProjectLayout(base)).apklibs
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
  def ndkBundle(sdkPath: String) = file(sdkPath) / "ndk-bundle"

  def sbtSubfolder = file(AndroidLocation.getFolder) / "sbt"
  def predex = sbtSubfolder / "predex"
  def explodedAars = sbtSubfolder / "exploded-aars"
  def androidHomeCache = sbtSubfolder / "sdk.dir"
  def androidNdkHomeCache = sbtSubfolder / "ndk.dir"
  // TODO consider using ~/.android-sdk
  def fallbackAndroidHome = sbtSubfolder / "sdk"
  def sdkLicenses = sbtSubfolder / "sdk-licenses"
  def sdkFallback(f: File): Option[String] = if (f.isFile) {
    Try(IO.readLines(f)).toOption.flatMap(_.headOption).map(file).collect {
      case d if d.isDirectory => d.getAbsolutePath + File.separator
    }
  } else None
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
        PluginFail(s"Both $base/AndroidManifest.xml and $base/src/main/AndroidManifest.xml exist, unable to determine project layout")
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
    override def gen = new BuildOutput.AndroidOutput(this).generatedSrc
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
    override def gen = new BuildOutput.AndroidOutput(this).generatedSrc
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
    override def testManifest = wrapped.manifest
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
    override def toString = "ProjectLayout.Wrapped(" + wrapped.toString + ")"
  }
}
