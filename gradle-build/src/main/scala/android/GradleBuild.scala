package android

import java.io.{File, _}
import java.util.concurrent.TimeUnit

import android.Keys._
import com.android.builder.model.{JavaLibrary, AndroidLibrary, MavenCoordinates}
import com.hanhuy.gradle.discovery.GradleBuildModel
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.gradle.tooling.{GradleConnector, ProjectConnection}
import sbt.Keys._
import sbt._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.Try

/**
 * @author pfnguyen
 */
trait GradleBuild extends Build {
  private[this] var projectsMap = Map.empty[String,Project]

  def gradle = projectsMap.apply _

  override def projects = {
    val start = System.currentTimeMillis
    val originalProjects = super.projects.map (p => (p.id, p)).toMap
    val initgradle = IO.readLinesURL(Tasks.resourceUrl("plugin-init.gradle"))
    val f = File.createTempFile("plugin-init", ".gradle")
    IO.writeLines(f, initgradle)
    f.deleteOnExit()

    println("Searching for android gradle projects...")
    val gconnection = GradleConnector.newConnector.asInstanceOf[DefaultGradleConnector]
    gconnection.daemonMaxIdleTime(60, TimeUnit.SECONDS)
    gconnection.setVerboseLogging(false)

    try {
      val discovered = processDirectoryAt(file("."), f, gconnection)._2
      f.delete()
      val projects = discovered map { case (p, deps) =>
        val orig = originalProjects.get(p.id)

        val ds = deps.toList.map (dep => discovered.find(_._1.id == dep).get._1)
        val p2 = p.settings(Plugin.buildWith(ds: _*): _*).dependsOn(ds map { d =>
          classpathDependency(Project.projectToRef(d)) }: _*)
        orig.fold(p2)(o => p2.copy(settings = o.asInstanceOf[ProjectDefinition[_]].settings ++ p.settings))
      }

      projectsMap = projects map { p => (p.id, p) } toMap

      val end = System.currentTimeMillis
      val elapsed = (end - start) / 1000.0f
      println(f"Discovered gradle projects (in $elapsed%.02fs):")
      println(projects.map(p => f"${p.id}%20s at ${p.base}").mkString("\n"))
      projects
    } catch {
      case ex: Exception =>
        @tailrec
        def collectMessages(e: Throwable, msgs: List[String] = Nil, seen: Set[Throwable] = Set.empty): String = {
          if (e == null || seen(e))
            msgs.mkString("\n")
          else
            collectMessages(e.getCause, e.getMessage :: msgs, seen + e)
        }
        throw new MessageOnlyException(collectMessages(ex))
    }
  }

  val nullsink = new OutputStream {
    override def write(b: Int) = ()
  }

  def modelBuilder[A](c: ProjectConnection, model: Class[A]) = {
    c.model(model)
      .setStandardOutput(nullsink)
      .setStandardError(nullsink)
  }
  def initScriptModelBuilder[A](c: ProjectConnection, model: Class[A], initscript: File) =
    modelBuilder(c, model).withArguments(
      "--init-script", initscript.getAbsolutePath)

  def gradleBuildModel(c: ProjectConnection, initscript: File) =
    initScriptModelBuilder(c, classOf[GradleBuildModel], initscript).get()

  def processDirectoryAt(base: File, initscript: File,
                         connector: GradleConnector,
                         repositories: List[Resolver] = Nil, seen: Set[File] = Set.empty): (Set[File],List[(Project,Set[String])]) = {
    val c = connector.forProjectDirectory(base).connect()
    val model = gradleBuildModel(c, initscript)
    val prj = model.getGradleProject
    val discovery = model.getDiscovery
    val repos = repositories ++ (
      model.getRepositories.getResolvers.asScala.toList map (r =>
        r.getUrl.toString at r.getUrl.toString))

    val (visited,subprojects) = prj.getChildren.asScala.toList.foldLeft((seen + base.getCanonicalFile,List.empty[(Project,Set[String])])) { case ((saw,acc),child) =>
      // gradle 2.4 added getProjectDirectory
      val childDir = Try(child.getProjectDirectory).getOrElse(file(child.getPath.replaceAll(":", ""))).getCanonicalFile
      if (!saw(childDir)) {
        println("Processing gradle sub-project at: " + childDir.getName)
        val (visited, subs) = processDirectoryAt(childDir, initscript, connector, repos, saw + childDir)
        (visited ++ saw, subs ++ acc)
      } else
        (saw,acc)
    }

    try {
      if (discovery.isApplication || discovery.isLibrary) {
        val ap = model.getAndroidProject
        val sourceVersion = ap.getJavaCompileOptions.getSourceCompatibility
        val targetVersion = ap.getJavaCompileOptions.getTargetCompatibility

        val default = ap.getDefaultConfig
        val flavor = default.getProductFlavor
        val sourceProvider = default.getSourceProvider

        val optional: List[Setting[_]] = Option(flavor.getApplicationId).toList.map {
          applicationId in Android := _
        } ++ Option(flavor.getVersionCode).toList.map {
          versionCode in Android := Some(_)
        } ++ Option(flavor.getVersionName).toList.map {
          versionName in Android := Some(_)
        } ++ Option(flavor.getMinSdkVersion).toList.map {
          minSdkVersion in Android := _.getApiString
        } ++ Option(flavor.getTargetSdkVersion).toList.map {
          targetSdkVersion in Android := _.getApiString
        } ++ Option(flavor.getRenderscriptTargetApi).toList.map {
          rsTargetApi in Android := _.toString
        } ++ Option(flavor.getRenderscriptSupportModeEnabled).toList.map {
          rsSupportMode in Android := _
        } ++ Option(flavor.getMultiDexEnabled).toList.map {
          dexMulti in Android := _
        } ++ Option(flavor.getMultiDexKeepFile).toList.map {
          dexMainFileClasses in Android := IO.readLines(_, IO.utf8)
        } ++ Option(model.getPackagingOptions).toList.map { po =>
          packagingOptions in Android := PackagingOptions(
            po.getExcludes.asScala.toList, po.getPickFirsts.asScala.toList, po.getMerges.asScala.toList
          )
        }
        val v = ap.getVariants.asScala.head
        val art = v.getMainArtifact
        def libraryDependency(m: MavenCoordinates) = {
          val module = m.getGroupId % m.getArtifactId % m.getVersion intransitive()
          val mID = if (m.getPackaging != "aar") module else Dependencies.aar(module)
          val classifier = Option(m.getClassifier)
          libraryDependencies += classifier.fold(mID)(mID.classifier)
        }

        val androidLibraries = art.getDependencies.getLibraries.asScala.toList
        val (aars,projects) = androidLibraries.partition(_.getProject == null)
        val (resolved,localAars) = aars.partition(a => Option(a.getResolvedCoordinates.getGroupId).exists(_.nonEmpty))
        val localAar = localAars.toList map { l =>
          android.Keys.localAars in Android += l.getBundle
        }
        def dependenciesOf[A](l: A, seen: Set[File] = Set.empty)(children: A => List[A])(fileOf: A => File): List[A] = {
          val deps = children(l) filterNot(l => seen(fileOf(l)))
          deps ++ deps.flatMap(d => dependenciesOf(d, seen ++ deps.map(fileOf))(children)(fileOf))
        }
        def aarDependencies(l: AndroidLibrary): List[AndroidLibrary] =
          dependenciesOf(l)(_.getLibraryDependencies.asScala.toList)(_.getBundle)
        def javaDependencies(l: JavaLibrary): List[JavaLibrary] =
          dependenciesOf(l)(_.getDependencies.asScala.toList)(_.getJarFile)
        val allAar = (resolved ++ resolved.flatMap(aarDependencies)).groupBy(
            _.getBundle.getCanonicalFile).map(_._2.head).toList

        val javalibs = art.getDependencies.getJavaLibraries.asScala.toList
        val allJar = (javalibs ++ javalibs.flatMap(javaDependencies)).groupBy(
          _.getJarFile.getCanonicalFile).map(_._2.head).toList

        val libs = allAar ++ allJar filter (j =>
          Option(j.getResolvedCoordinates.getGroupId).exists(_.nonEmpty)) map { j =>
          libraryDependency(j.getResolvedCoordinates)
        }

        val p = Project(base = base, id = ap.getName).settings(
          (if (discovery.isApplication) Plugin.androidBuild else Plugin.androidBuildAar): _*).settings(
          resolvers ++= repos,
          platformTarget in Android := ap.getCompileTarget,
          name := ap.getName,
          javacOptions in Compile ++= "-source" :: sourceVersion :: "-target" :: targetVersion :: Nil,
          buildConfigOptions in Android ++= flavor.getBuildConfigFields.asScala.toList map { case (key, field) =>
            (field.getType, key, field.getValue)
          },
          resValues in Android ++= flavor.getResValues.asScala.toList map { case (key, field) =>
            (field.getType, key, field.getValue)
          },
          debugIncludesTests in Android := false, // default because can't express it easily otherwise
          proguardOptions in Android ++= flavor.getProguardFiles.asScala.toList.flatMap(IO.readLines(_, IO.utf8)),
          manifestPlaceholders in Android ++= flavor.getManifestPlaceholders.asScala.toMap map { case (k,o) => (k,o.toString) },
          projectLayout in Android := new ProjectLayout.Wrapped((projectLayout in Android).value) {
            override def manifest = sourceProvider.getManifestFile
            override def javaSource = sourceProvider.getJavaDirectories.asScala.head
            override def resources = sourceProvider.getResourcesDirectories.asScala.head
            override def res = sourceProvider.getResDirectories.asScala.head
            override def renderscript = sourceProvider.getRenderscriptDirectories.asScala.head
            override def aidl = sourceProvider.getAidlDirectories.asScala.head
            override def assets = sourceProvider.getAssetsDirectories.asScala.head
            override def jniLibs = sourceProvider.getJniLibsDirectories.asScala.head
          }
        ).settings(optional: _*).settings(libs: _*).settings(localAar: _*)
        (visited, (p, projects.map(_.getProject.replaceAll(":","")).toSet) :: subprojects)
      } else
        (visited, subprojects)
    } finally {
      c.close()
    }
  }
}
