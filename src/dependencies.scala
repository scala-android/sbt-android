package android

import sbt._

import scala.collection.JavaConverters._
import scala.xml.XML
import language.postfixOps

import com.android.builder.dependency.JarDependency
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}

import BuildOutput._

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
    def layout: ProjectLayout

    def path = layout.base

    override def getExternalAnnotations = path / FN_ANNOTATIONS_ZIP
    override def getName = path.getCanonicalPath
    override def getProject = null
    override def getBundle = null
    override def getManifest = layout.manifest
    override def getFolder = path

    // new in builder 1.3.0
    override def getPublicResources = path / FN_PUBLIC_TXT
    // try to figure out how to identify this from a library?
    override def isOptional = false

    override def getJarFile = path / FN_CLASSES_JAR
    override def getLocalJars = ((path / LIBS_FOLDER) ** "*.jar" get).asJava
    override def getResFolder = path / FD_RES
    override def getAssetsFolder = path / FD_ASSETS
    override def getJniFolder = path / "jni"
    override def getSymbolFile = path / "R.txt"
    override def getAidlFolder = path / FD_AIDL
    override def getRenderscriptFolder = path / FD_RENDERSCRIPT
    override def getLintJar = path / "lint.jar"
    override def getProguardRules = path / "proguard.txt"

    override def getDependencies: java.util.List[AndroidLibrary] =
      Seq.empty.asJava
    override def getLibraryDependencies = getDependencies
    override def getManifestDependencies = getDependencies

    override def getLocalDependencies = getLocalJars.asScala map {
      j => new JarDependency(j, true, true, false, null, null)
    } asJava

    override def getProjectVariant = null

    override def getRequestedCoordinates = null

    override def getResolvedCoordinates = null
  }

  case class ApkLibrary(base: File) extends LibraryDependency with Pkg {
    implicit val output = (p: ProjectLayout) => new AndroidOutput(p)
    def target = path
    import com.android.SdkConstants._

    // apklib are always ant-style layouts
    override lazy val layout = ProjectLayout.Ant(base)
    lazy val pkg = XML.loadFile(getManifest).attribute("package").head.text

    override def getJniFolder = layout.libs
    override def getSymbolFile = layout.rTxt
    override def getJarFile = path / "bin" / FN_CLASSES_JAR
  }
  def moduleIdFile(path: File) = path / "sbt-module-id"
  case class AarLibrary(base: File) extends LibraryDependency {
    lazy val moduleID: ModuleID = {
      val mfile = moduleIdFile(path)
      val parts = IO.readLines(mfile).head.split(":")
      parts(0) % parts(1) % parts(2)
    }
    override lazy val layout = new ProjectLayout.Ant(base) {
      override def jniLibs = getJniFolder
    }
    override def getJniFolder = path / "jni"
  }

  case class LibraryProject(layout: ProjectLayout) extends LibraryDependency {
    implicit val output = (p: ProjectLayout) => new AndroidOutput(p)
    import com.android.SdkConstants._

    override def getSymbolFile = layout.rTxt
    override def getJarFile = layout.classesJar
    override def getProguardRules = layout.proguardTxt

    override def getPublicResources = layout.publicTxt

    override def getJniFolder = layout.jniLibs
    override def getLocalJars = (layout.libs ** "*.jar" get).asJava
    override def getResFolder = layout.res
    override def getAssetsFolder = layout.assets
    override def getAidlFolder = layout.aidl
    override def getRenderscriptFolder = layout.renderscript

    override def getDependencies = {
      ((IO.listFiles(layout.aars) filter (_.isDirectory) map { d =>
        AarLibrary(d): AndroidLibrary
      }) ++ (IO.listFiles(layout.apklibs) filter (_.isDirectory) map { d =>
        ApkLibrary(d): AndroidLibrary
      })).toList.asJava
    }
  }

  object LibraryProject {
    def apply(base: File): LibraryProject = LibraryProject(ProjectLayout(base))
  }

  trait Pkg {
    def pkg: String
  }
  object AutoLibraryProject {
    def apply(path: File) = new AutoLibraryProject(path)
  }
  class AutoLibraryProject(path: File)
  extends LibraryProject(ProjectLayout(path)) with Pkg {
    lazy val pkg = XML.loadFile(getManifest).attribute("package").head.text

    override def equals(obj: scala.Any) = obj match {
      case l: LibraryProject =>
        l.path.getCanonicalFile == path.getCanonicalFile
      case _ => false
    }

    override def hashCode() = path.getCanonicalFile.hashCode
  }

  implicit class RichProject(val project: Project) extends AnyVal {
    def androidBuildWith(deps: ProjectReference*): Project = {
      project.settings(Plugin.androidBuild ++ Plugin.buildWith(deps:_*):_*) dependsOn (
        deps map { x => x: ClasspathDep[ProjectReference] }:_*)
    }
  }
}
// vim: set ts=2 sw=2 et:
