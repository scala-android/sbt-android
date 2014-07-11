package android

import android.Keys.ProjectLayout
import sbt._

import scala.collection.JavaConversions._
import scala.xml.XML

import com.android.builder.dependency.JarDependency
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}

object Dependencies {
  // excludes are temporary until everything/one uses libraryDependencies
  // and only one version of the support libs
  def artifacts(m: ModuleID, name: String, exttype: String) =
    m.artifacts(Artifact(name, exttype, exttype)) exclude (
      "com.google.android", "support-v4") exclude (
      "com.google.android", "support-v13")

  def apklib(m: ModuleID): ModuleID            = artifacts(m, m.name, "apklib")
  def aar(m: ModuleID): ModuleID               = artifacts(m, m.name, "aar")
  def apklib(m: ModuleID, n: String): ModuleID = artifacts(m, n, "apklib")
  def aar(m: ModuleID, n: String): ModuleID    = artifacts(m, n, "aar")

  trait LibraryDependency extends AndroidLibrary {
    import com.android.SdkConstants._
    def path: File
    val layout = Keys.ProjectLayout(path)

    override def getName = path.getCanonicalPath
    override def getProject = null
    override def getBundle = null
    override def getManifest = layout.manifest
    override def getFolder = path
    override def getJarFile = path / FN_CLASSES_JAR
    override def getLocalJars = (path / LIBS_FOLDER) ** "*.jar" get
    override def getResFolder = path / FD_RES
    override def getAssetsFolder = path / FD_ASSETS
    override def getJniFolder = path / "jni"
    override def getSymbolFile = path / "R.txt"
    override def getAidlFolder = path / FD_AIDL
    override def getRenderscriptFolder = path / FD_RENDERSCRIPT
    override def getLintJar = path / "lint.jar"
    override def getProguardRules = path / "proguard.txt"

    override def getDependencies = Seq.empty[LibraryDependency]
    override def getLibraryDependencies = getDependencies
    override def getManifestDependencies = getDependencies

    override def getLocalDependencies = getLocalJars map {
      j => new JarDependency(j, true, false)
    }
    override def getProjectVariant = null

    override def getRequestedCoordinates = null

    override def getResolvedCoordinates = null
  }

  case class ApkLibrary(path: File) extends LibraryDependency with Pkg {
    import com.android.SdkConstants._
    lazy val pkg = XML.loadFile(getManifest).attribute("package").get(0).text

    override def getJniFolder = layout.libs
    override def getSymbolFile = path / "gen" / "R.txt"
    override def getJarFile = path / "bin" / FN_CLASSES_JAR
  }
  case class AarLibrary(path: File) extends LibraryDependency {
    override val layout = new ProjectLayout.Ant(path) {
      override def jniLibs = getJniFolder
    }
    override def getJniFolder = path / "jni"
  }

  case class LibraryProject(path: File) extends LibraryDependency {
    import com.android.SdkConstants._

    override def getSymbolFile = layout.gen / "R.txt"
    override def getJarFile = layout.bin / FN_CLASSES_JAR
    override def getProguardRules = layout.bin / "proguard.txt"
    override def getJniFolder = layout.jniLibs
    override def getLocalJars = layout.libs ** "*.jar" get
    override def getResFolder = layout.res
    override def getAssetsFolder = layout.assets
    override def getAidlFolder = layout.aidl
    override def getRenderscriptFolder = layout.renderscript

    override def getDependencies = {
      Option((path / "target" / "aars").listFiles).map { list =>
        list filter (_.isDirectory) map { d =>
          AarLibrary(d): LibraryDependency
        }
      }.getOrElse(Array.empty).toSeq ++
        Option((path / "target" / "apklibs").listFiles).map { list =>
          list filter (_.isDirectory) map { d =>
            ApkLibrary(d): LibraryDependency
          }
        }.getOrElse(Array.empty).toSeq
    }
  }
  trait Pkg {
    def pkg: String
  }
  object AutoLibraryProject {
    def apply(path: File) = new AutoLibraryProject(path)
  }
  class AutoLibraryProject(override val path: File)
  extends LibraryProject(path) with Pkg {
    lazy val pkg = XML.loadFile(getManifest).attribute("package").get(0).text

    override def equals(obj: scala.Any) = obj match {
      case l: LibraryProject =>
        l.path.getCanonicalFile == path.getCanonicalFile
      case _ => false
    }

    override def hashCode() = path.getCanonicalFile.hashCode
  }
}
// vim: set ts=2 sw=2 et:
