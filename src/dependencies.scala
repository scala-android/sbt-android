package android

import sbt._

import scala.collection.JavaConverters._
import scala.xml.XML
import language.postfixOps
import BuildOutput._
import com.android.builder.dependency.level2.AndroidDependency
import com.android.builder.model.{AndroidLibrary, JavaLibrary, MavenCoordinates}
import com.android.manifmerger.ManifestProvider

object Dependencies {
  // excludes are temporary until everything/one uses libraryDependencies
  // and only one version of the support libs
  def artifacts(m: ModuleID, name: String, exttype: String): ModuleID =
    m.artifacts(Artifact(name, exttype, exttype)) exclude (
      "com.google.android", "support-v4") exclude (
      "com.google.android", "support-v13")

  def apklib(m: ModuleID): ModuleID            = artifacts(m, m.name, "apklib")
  def aar(m: ModuleID): ModuleID               = artifacts(m, m.name, "aar")
  def apklib(m: ModuleID, n: String): ModuleID = artifacts(m, n, "apklib")
  def aar(m: ModuleID, n: String): ModuleID    = artifacts(m, n, "aar")

  trait LibraryDependency extends AndroidLibrary with ManifestProvider with Pkg {
    import com.android.SdkConstants._
    def layout: ProjectLayout

    def asAndroidDependency: Option[AndroidDependency]

    def path: File = layout.base

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

    private[this] def getDependencies: java.util.List[AndroidLibrary] =
      Seq.empty.asJava
    override def getLibraryDependencies = getDependencies

    override def getProjectVariant = null

    override def getRequestedCoordinates = null

    override def getResolvedCoordinates = null

    override def isProvided = false

    override def getJavaDependencies: java.util.List[JavaLibrary] = List.empty.asJava

    override def isSkipped = false
  }

  case class ApkLibrary(base: File) extends LibraryDependency with Pkg {
    implicit val output: BuildOutput.Converter = new AndroidOutput(_)
    def target = path
    import com.android.SdkConstants._

    override def asAndroidDependency = Some(AndroidDependency.createLocalTestedAarLibrary(getJarFile, pkg, base.getAbsolutePath, base))

    // apklib are always ant-style layouts
    override lazy val layout = ProjectLayout.Ant(base)
    lazy val pkg: String = XML.loadFile(getManifest).attribute("package").head.text

    override def getJniFolder = layout.libs
    override def getSymbolFile = layout.rTxt
    override def getJarFile = path / "bin" / FN_CLASSES_JAR
  }
  def moduleIdFile(path: File) = path / "sbt-module-id"
  case class AarLibrary(base: File) extends LibraryDependency with Pkg {
    override def asAndroidDependency = Some(AndroidDependency.createExplodedAarLibrary(getJarFile, moduleID.asMavenCoordinates, pkg, base.getAbsolutePath, base))
    lazy val moduleID: ModuleID = {
      val mfile = moduleIdFile(path)
      val parts = IO.readLines(mfile).headOption.fold(PluginFail(
       s"Failed to load ModuleID info from $mfile; extracted aar is corrupted"
      ))(_.split(":"))
      if (parts.length < 3)
        PluginFail(s"Failed to read ModuleID info from $mfile; extracted aar is corrupted")
      parts(0) % parts(1) % parts(2)
    }
    override lazy val layout = new ProjectLayout.Ant(base) {
      override def jniLibs = getJniFolder
    }
    override def getJniFolder = path / "jni"
    lazy val pkg: String = XML.loadFile(getManifest).attribute("package").head.text
  }

  case class LibraryProject(layout: ProjectLayout, extraRes: Seq[File], extraAssets: Seq[File])
                           (implicit output: BuildOutput.Converter) extends LibraryDependency with Pkg {

    override def asAndroidDependency = None
    lazy val pkg: String = XML.loadFile(getManifest).attribute("package").head.text
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

    override def getLibraryDependencies = ((IO.listFiles(layout.aars) map {
      case d if d.isDirectory => AarLibrary(d)
      case f if f.isFile      => AarLibrary(SdkLayout.explodedAars / f.getName)
    }) ++ (IO.listFiles(layout.apklibs) filter (_.isDirectory) map { d =>
      ApkLibrary(d): AndroidLibrary
    })).toList.asJava
  }

  case class LibEquals[A <: AndroidLibrary](lib: A) {
    override def equals(other: Any): Boolean = {
      (lib, other) match {
        case (l @ AarLibrary(_), LibEquals(r @ AarLibrary(_))) ⇒
          l.moduleID == r.moduleID
        case (l @ LibraryProject(_,_,_), LibEquals(r @ LibraryProject(_,_,_))) ⇒
          l.path.getCanonicalFile == r.path.getCanonicalFile
        case _ ⇒ false
      }
    }

    override def hashCode(): Int = lib match {
      case a@AarLibrary(_) => a.moduleID.hashCode
      case _ => lib.getFolder.hashCode
    }
  }

  implicit class LibrarySeqOps[A <: AndroidLibrary](libs: Seq[A]) {
    def distinctLibs: Seq[A] = libs.map(LibEquals.apply).distinct.map(_.lib)
  }

  object LibraryProject {
    def apply(base: File)(implicit m: BuildOutput.Converter = new BuildOutput.AndroidOutput(_)): LibraryProject =
      LibraryProject(ProjectLayout(base), Nil, Nil)
  }

  trait Pkg {
    def pkg: String
  }
  object AutoLibraryProject {
    def apply(path: File)(implicit m: BuildOutput.Converter) = new AutoLibraryProject(path)
  }
  class AutoLibraryProject(path: File)(implicit m: BuildOutput.Converter)
  extends LibraryProject(ProjectLayout(path), Nil, Nil) {
    override def equals(obj: scala.Any): Boolean = obj match {
      case l: LibraryProject =>
        l.path.getCanonicalFile == path.getCanonicalFile
      case _ => false
    }

    override def hashCode(): Int = path.getCanonicalFile.hashCode
  }

  @deprecated("`dependsOn(project)` now adds transitive settings automatically", "1.7.0")
  implicit class RichProject(val project: Project) extends AnyVal {
    @deprecated("use `enablePlugins(AndroidApp).dependsOn(deps)`", "1.7.0")
    def androidBuildWith(deps: ProjectReference*): Project = {
      project.enablePlugins(AndroidApp).dependsOn(deps map { x => x: ClasspathDep[ProjectReference] }: _*)
    }
  }

  implicit class ModuleIDOps(id: ModuleID) {
    def revMatches(other: String): Boolean = {
      def partMatches(p: (String, String)) = p._1 == p._2 || p._1 == "+"
      val parts = id.revision.split('.')
      val otherParts = other.split('.')
      val partsMatch = parts zip otherParts forall partMatches
      partsMatch && (
        parts.length == otherParts.length ||
        (parts.length < otherParts.length) && parts.lastOption.exists(_ == "+")
      )
    }

    def matches(other: ModuleID) = {
      id.organization == other.organization && id.name == other.name &&
        revMatches(other.revision)
    }

    def asMavenCoordinates: MavenCoordinates = new MavenCoordinates {
      override def getVersion = id.revision
      override def getGroupId = id.organization

      override def getClassifier = id.explicitArtifacts.collect {
        case a if a.classifier.nonEmpty => a.classifier.get
      }.headOption.orNull

      override lazy val getVersionlessId: String =
        id.organization + ":" + id.name + Option(getClassifier).fold("")(":" + _)
      override def getPackaging = "jar"
      override def getArtifactId = id.name
    }
  }

  implicit class ProjectRefOps(project: ProjectRef)(implicit struct: BuildStructure) {
    def resolved: Option[ResolvedProject] = Project.getProject(project, struct)

    def deps: Seq[ProjectRef] = resolved.map(_.dependencies) getOrElse Nil map(_.project)

    def deepDeps: Seq[ProjectRef] = (deps.flatMap(_.deepDeps) :+ project).distinct

    def libraryDependencies: Seq[ModuleID] =
      (sbt.Keys.libraryDependencies in project)
        .get(struct.data)
        .getOrElse(Nil)

    def dependsOn(id: ModuleID): Boolean = libraryDependencies exists(_.matches(id))
  }
}
// vim: set ts=2 sw=2 et:
