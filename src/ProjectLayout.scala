package android

import sbt._

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
  def gen: File
  def bin: File
  def libs: File
  def aidl: File
  def jni: File
  def jniLibs: File
  def renderscript: File
}
object ProjectLayout {
  def apply(base: File) = {
    if ((base / "src" / "main" / "AndroidManifest.xml").isFile) {
      if ((base / "AndroidManifest.xml").isFile) {
        Plugin.fail(s"Both ${base}/AndroidManifest.xml and ${base}/src/main/AndroidManifest.xml exist, unable to determine project layout");
      }
      ProjectLayout.Gradle(base)
    } else {
      ProjectLayout.Ant(base)
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
    override def gen = base / "gen"
    override def bin = base / "bin"
    override def libs = base / "libs"
    override def aidl = sources
    override def jni = base / "jni"
    override def jniLibs = libs
    override def renderscript = sources
  }
  case class Gradle(base: File) extends ProjectLayout {
    override def manifest = sources / "AndroidManifest.xml"
    override def testSources = base / "src" / "androidTest"
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
    override def gen = base / "target" / "android-gen"
    override def bin = base / "target" / "android-bin"
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
