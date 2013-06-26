package android
import sbt._

import scala.collection.JavaConversions._
import scala.xml.XML

import com.android.builder.dependency.JarDependency
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}

object Dependencies {
  // excludes are temporary until everything/one uses libraryDependencies
  def artifacts(mid: ModuleID, name: String, exttype: String) =
    mid.artifacts(Artifact(name, exttype, exttype)) exclude (
      "com.google.android", "support-v4") exclude (
      "com.google.android", "support-v13")

  def apklib(m: ModuleID): ModuleID            = artifacts(m, m.name, "apklib")
  def aar(m: ModuleID): ModuleID               = artifacts(m, m.name, "aar")
  def apklib(m: ModuleID, n: String): ModuleID = artifacts(m, n, "apklib")
  def aar(m: ModuleID, n: String): ModuleID    = artifacts(m, n, "aar")

  class LibraryDependency(val path: File) extends AndroidLibrary {
    import com.android.SdkConstants._
    private val manifest = Seq(path / FN_ANDROID_MANIFEST_XML,
      path / "src" / "main" / FN_ANDROID_MANIFEST_XML) collect {
      case f if f.exists => f
    } head

    val pkg = XML.loadFile(manifest).attribute("package").get(0).text

    val binPath = Tasks.directoriesList(
      "out.dir", "bin", Tasks.loadProperties(path), path)(0)

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

    override def getDependencies = {
      (Option((path / "target" / "aars").listFiles).map { list =>
        list filter (_.isDirectory) map { d =>
          AarLibrary(d): LibraryDependency
        }
      }.flatten.toSeq) ++
        (Option((path / "target" / "apklibs").listFiles).map { list =>
          list filter (_.isDirectory) map { d =>
            ApkLibrary(d): LibraryDependency
          }
        }.flatten.toSeq)
    }
    override def getLibraryDependencies = getDependencies
    override def getManifestDependencies = getDependencies

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
// vim: set ts=2 sw=2 et:
