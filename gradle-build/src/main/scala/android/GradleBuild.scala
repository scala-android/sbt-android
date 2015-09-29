package android

import java.io.{OutputStream, File}
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
  val generatedScript = file(".") / "00-gradle-generated.sbt"

  def inGradleProject(project: String)(ss: Seq[Setting[_]]): Seq[Setting[_]] =
    inScope(ThisScope.copy(project = Select(LocalProject(project))))(ss)

  def importFromGradle(): Unit = {
    val start = System.currentTimeMillis
    val originalProjects = super.projects.map (p => (p.id, p)).toMap
    val initgradle = IO.readLinesURL(Tasks.resourceUrl("plugin-init.gradle"))
    val f = File.createTempFile("plugin-init", ".gradle")
    IO.writeLines(f, initgradle)
    f.deleteOnExit()

    if (generatedScript.exists) {
      println("Updating project because gradle scripts have changed")
    }

    println("Searching for android gradle projects...")
    val gconnection = GradleConnector.newConnector.asInstanceOf[DefaultGradleConnector]
    gconnection.daemonMaxIdleTime(60, TimeUnit.SECONDS)
    gconnection.setVerboseLogging(false)

    try {
      val discovered = GradleBuildSerializer.toposort(processDirectoryAt(file("."), f, gconnection)._2)
      f.delete()
      val projects = discovered map { sp =>
        val deps = sp.dependencies
        val p = sp.project
        val orig = originalProjects.get(p.id)

        val ds = deps.toList.map (dep => discovered.find(_.id == dep).get.project)
        val p2 = p.settings(Plugin.buildWith(ds: _*): _*).dependsOn(ds map { d =>
          classpathDependency(Project.projectToRef(d)) }: _*)
        orig.fold(p2)(o => p2.copy(settings = o.asInstanceOf[ProjectDefinition[_]].settings ++ p.settings))
      }

      val end = System.currentTimeMillis
      val elapsed = (end - start) / 1000.0f
      println(f"Discovered gradle projects (in $elapsed%.02fs):")
      println(projects.map(p => f"${p.id}%20s at ${p.base}").mkString("\n"))
      IO.writeLines(generatedScript, discovered map (_.serialized))
    } catch {
      case ex: Exception =>
        @tailrec
        def collectMessages(e: Throwable, msgs: List[String] = Nil, seen: Set[Throwable] = Set.empty): String = {
          if (e == null || seen(e))
            msgs.mkString("\n")
          else
            collectMessages(e.getCause, e.getMessage :: msgs, seen + e)
        }
        if (ex.getCause == null || ex == ex.getCause)
          throw ex
        else
          throw new MessageOnlyException(collectMessages(ex))
    }
  }

  def checkGeneratedScript(): Unit = {
    val base = file(".")
    val gradles = base ** "*.gradle" get
    val lastModified = generatedScript.lastModified
    if (gradles exists (_.lastModified > lastModified))
      importFromGradle()
    else {
      println("Loading cached gradle project definitions")
    }
  }

  checkGeneratedScript()

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

  import GradleBuildSerializer._
  def processDirectoryAt(base: File, initscript: File,
                         connector: GradleConnector,
                         repositories: List[Resolver] = Nil, seen: Set[File] = Set.empty): (Set[File],List[SbtProject]) = {
    val c = connector.forProjectDirectory(base).connect()
    val model = gradleBuildModel(c, initscript)
    val prj = model.getGradleProject
    val discovery = model.getDiscovery
    val repos = repositories ++ (
      model.getRepositories.getResolvers.asScala.toList map (r =>
        r.getUrl.toString at r.getUrl.toString))

    val (visited,subprojects) = prj.getChildren.asScala.toList.foldLeft((seen + base.getCanonicalFile,List.empty[SbtProject])) { case ((saw,acc),child) =>
      // gradle 2.4 added getProjectDirectory
      val childDir = Try(child.getProjectDirectory).getOrElse(file(child.getPath.replace(":", ""))).getCanonicalFile
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

        val optional: List[SbtSetting] = Option(flavor.getApplicationId).toList.map {
          applicationId in Android /:= _
        } ++ Option(flavor.getVersionCode).toList.map {
          versionCode in Android /:= Some(_)
        } ++ Option(flavor.getVersionName).toList.map {
          versionName in Android /:= Some(_)
        } ++ Option(flavor.getMinSdkVersion).toList.map {
          minSdkVersion in Android /:= _.getApiString
        } ++ Option(flavor.getTargetSdkVersion).toList.map {
          targetSdkVersion in Android /:= _.getApiString
        } ++ Option(flavor.getRenderscriptTargetApi).toList.map {
          rsTargetApi in Android /:= _.toString
        } ++ Option(flavor.getRenderscriptSupportModeEnabled).toList.map {
          rsSupportMode in Android /:= _
        } ++ Option(flavor.getMultiDexEnabled).toList.map {
          dexMulti in Android /:= _
        } ++ Option(flavor.getMultiDexKeepFile).toList.map {
          dexMainClasses in Android /:= IO.readLines(_, IO.utf8)
        } ++ Option(model.getPackagingOptions).toList.map {
          p => packagingOptions in Android /:= PackagingOptions(
            p.getExcludes.asScala.toList, p.getPickFirsts.asScala.toList, p.getMerges.asScala.toList
          )
        }
        val v = ap.getVariants.asScala.head
        val art = v.getMainArtifact
        def libraryDependency(m: MavenCoordinates) = {
          val module = m.getGroupId % m.getArtifactId % m.getVersion intransitive()
          val mID = if (m.getPackaging != "aar") module else Dependencies.aar(module)
          val classifier = Option(m.getClassifier)
          libraryDependencies /+= classifier.fold(mID)(mID.classifier)
        }

        val androidLibraries = art.getDependencies.getLibraries.asScala.toList
        val (aars,projects) = androidLibraries.partition(_.getProject == null)
        val (resolved,localAars) = aars.partition(a => Option(a.getResolvedCoordinates.getGroupId).exists(_.nonEmpty))
        val localAar = localAars.toList map {
          android.Keys.localAars in Android /+= _.getBundle
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

        val libs = allAar ++ allJar filter { j =>
          Option(j.getResolvedCoordinates).exists { c =>
            val g = c.getGroupId
            val n = c.getArtifactId
            g.nonEmpty && (g != "com.google.android" || !n.startsWith("support-"))
          }
        } map { j =>
          libraryDependency(j.getResolvedCoordinates)
        }

        val unmanaged = allJar filter (_.getResolvedCoordinates == null) map { j =>
          unmanagedJars in Compile /+= Attributed.blank(j.getJarFile)
        }

        def extraDirectories(dirs: java.util.Collection[File], key: SettingKey[Seq[File]]): Seq[SbtSetting] =
          dirs.asScala.tail.map(d => key /+= d).toSeq

        val standard = List(
          resolvers /++= repos,
          platformTarget in Android /:= ap.getCompileTarget,
          name /:= ap.getName,
          javacOptions in Compile /++= "-source" :: sourceVersion :: "-target" :: targetVersion :: Nil,
          buildConfigOptions in Android /++= flavor.getBuildConfigFields.asScala.toList map { case (key, field) =>
            (field.getType, key, field.getValue)
          },
          resValues in Android /++= flavor.getResValues.asScala.toList map { case (key, field) =>
            (field.getType, key, field.getValue)
          },
          debugIncludesTests in Android /:= false, // default because can't express it easily otherwise
          proguardOptions in Android /++= flavor.getProguardFiles.asScala.toList.flatMap(IO.readLines(_, IO.utf8)),
          manifestPlaceholders in Android /++= flavor.getManifestPlaceholders.asScala.toMap map { case (k,o) => (k,o.toString) },
          projectLayout in Android /:= new ProjectLayout.Wrapped(ProjectLayout(base)) {
            override def manifest = sourceProvider.getManifestFile
            override def javaSource = sourceProvider.getJavaDirectories.asScala.head
            override def resources = sourceProvider.getResourcesDirectories.asScala.head
            override def res = sourceProvider.getResDirectories.asScala.head
            override def renderscript = sourceProvider.getRenderscriptDirectories.asScala.head
            override def aidl = sourceProvider.getAidlDirectories.asScala.head
            override def assets = sourceProvider.getAssetsDirectories.asScala.head
            override def jniLibs = sourceProvider.getJniLibsDirectories.asScala.head
          }
        ) ++ extraDirectories(sourceProvider.getJavaDirectories, sourceDirectories in Compile) ++
          extraDirectories(sourceProvider.getResDirectories, extraResDirectories in Android) ++
          extraDirectories(sourceProvider.getResourcesDirectories, resourceDirectories in Compile) ++
          extraDirectories(sourceProvider.getAssetsDirectories, extraAssetDirectories in Android)
        val sp = SbtProject(ap.getName, base, discovery.isApplication,
          projects.map(_.getProject.replace(":","")).toSet,
          optional ++ libs ++ localAar ++ standard ++ unmanaged)
        (visited, sp :: subprojects)
      } else
        (visited, subprojects)
    } finally {
      c.close()
    }
  }
}

object Serializer {
  def enc[T : Encoder](t: T): String = implicitly[Encoder[T]].encode(t)

  trait Encoder[T] {
    def encode(t: T): String
  }

  implicit val stringEncoder = new Encoder[String] {
    def encode(s: String) = "raw\"\"\"" + s + "\"\"\""
  }
  implicit val moduleEncoder = new Encoder[ModuleID] {
    def encode(m: ModuleID) = {
      val base = s""""${m.organization}" % "${m.name}" % "${m.revision}""""
      base + m.configurations.fold("")(c => s""" % "$c"""") + m.explicitArtifacts.map(
        a =>
          if (a.classifier.isDefined) {
            s" classifier(${enc(a.classifier.get)})"
          } else
            s""" artifacts(Artifact(${enc(a.name)}, ${enc(a.`type`)}, ${enc(a.extension)}))"""
      ).mkString("") + (if (m.isTransitive) "" else " intransitive()")
    }
  }
  implicit def seqEncoder[T : Encoder] = new Encoder[Seq[T]] {
    def encode(l: Seq[T]) = if (l.isEmpty) "Nil" else "Seq(" + l.map(i => enc(i)).mkString(",") + ")"
  }
  implicit val packagingOptionsEncoding = new Encoder[PackagingOptions] {
    def encode(p: PackagingOptions) =
      s"android.Keys.PackagingOptions(${enc(p.excludes)}, ${enc(p.pickFirsts)}, ${enc(p.merges)})"
  }
  implicit val fileEncoder = new Encoder[File] {
    def encode(f: File) = s"file(${enc(f.getAbsolutePath)})"
  }
  implicit val antProjectLayoutEncoding = new Encoder[ProjectLayout.Ant] {
    def encode(p: ProjectLayout.Ant) =
      s"ProjectLayout.Ant(${enc(p.base)})"
  }
  implicit val gradleProjectLayoutEncoding = new Encoder[ProjectLayout.Gradle] {
    def encode(p: ProjectLayout.Gradle) =
      s"ProjectLayout.Gradle(${enc(p.base)})"
  }
  implicit val ProjectLayoutEncoding = new Encoder[ProjectLayout] {
    def encode(p: ProjectLayout) = p match {
      case x: ProjectLayout.Ant => enc(x)
      case x: ProjectLayout.Gradle => enc(x)
      case x: ProjectLayout.Wrapped =>
        s"""
           |      new ProjectLayout.Wrapped(ProjectLayout(baseDirectory.value)) {
           |        override def base = ${enc(x.base)}
           |        override def resources = ${enc(x.resources)}
           |        override def testSources = ${enc(x.testSources)}
           |        override def sources = ${enc(x.sources)}
           |        override def javaSource = ${enc(x.javaSource)}
           |        override def libs = ${enc(x.libs)}
           |        override def gen = ${enc(x.gen)}
           |        override def testRes = ${enc(x.testRes)}
           |        override def manifest = ${enc(x.manifest)}
           |        override def testManifest = ${enc(x.testManifest)}
           |        override def scalaSource = ${enc(x.scalaSource)}
           |        override def aidl = ${enc(x.aidl)}
           |        override def bin = ${enc(x.bin)}
           |        override def renderscript = ${enc(x.renderscript)}
           |        override def testScalaSource = ${enc(x.testScalaSource)}
           |        override def testAssets = ${enc(x.testAssets)}
           |        override def jni = ${enc(x.jni)}
           |        override def assets = ${enc(x.assets)}
           |        override def testJavaSource = ${enc(x.testJavaSource)}
           |        override def jniLibs = ${enc(x.jniLibs)}
           |        override def res = ${enc(x.res)}
           |      }""".stripMargin
    }
  }
  implicit val wrappedProjectLayoutEncoding = new Encoder[ProjectLayout.Wrapped] {
    def encode(p: ProjectLayout.Wrapped) =
      s"new ProjectLayout.Wrapped(${enc(p.wrapped)})"
  }
  implicit val boolEncoder = new Encoder[Boolean] {
    def encode(b: Boolean) = b.toString
  }
  implicit val intEncoder = new Encoder[Int] {
    def encode(i: Int) = i.toString
  }
  implicit def listEncoder[T : Encoder] = new Encoder[List[T]] {
    def encode(l: List[T]) = if (l.isEmpty) "Nil" else "List(" + l.map(i => enc(i)).mkString(",\n      ") + ")"
  }
  implicit def optionEncoder[T : Encoder] = new Encoder[Option[T]] {
    def encode(l: Option[T]) = if (l.isEmpty) "None" else "Some(" + enc(l.get) + ")"
  }
  implicit def someEncoder[T : Encoder] = new Encoder[Some[T]] {
    def encode(l: Some[T]) = "Some(" + enc(l.get) + ")"
  }
  implicit val resolverEncoder = new Encoder[Resolver] {
    def encode(r: Resolver) = r match {
      case MavenRepository(n, root) => enc(n) + " at " + enc(root)
      case _ => throw new UnsupportedOperationException("Cannot handle: " + r)
    }
  }
  implicit def tuple3Encoder[A : Encoder,B : Encoder,C : Encoder] = new Encoder[(A,B,C)] {
    override def encode(t: (A, B, C)) = s"(${enc(t._1)}, ${enc(t._2)}, ${enc(t._3)})"
  }
  implicit def tuple2Encoder[A : Encoder,B : Encoder] = new Encoder[(A,B)] {
    override def encode(t: (A, B)) = s"(${enc(t._1)}, ${enc(t._2)})"
  }
  implicit def mapEncoder[A : Encoder,B : Encoder] = new Encoder[Map[A,B]] {
    override def encode(t: Map[A, B]) = s"Map(${t.toList.map(e => enc(e)).mkString(",\n      ")})"
  }
  implicit def attributedEncoder[T : Encoder] = new Encoder[Attributed[T]] {
    def encode(l: Attributed[T]) = s"Attributed.blank(${enc(l.data)})"
  }

  sealed trait Op {
    def serialized: String
  }

  def serialize[T : Manifest, U : Encoder](k: SettingKey[T], op: String, value: U) =
    key(k) + " " + op + " " + enc(value)
  def serialize[T : Manifest, U : Encoder](k: TaskKey[T], op: String, value: U) =
    key(k) + " " + op + " " + enc(value)
  def config(s: Scope) =
    s.config.toOption.fold("")(c => s""" in config("${c.name}")""")
  def typeName[T](implicit manifest: Manifest[T]): String = {
    def capitalized(m: Manifest[_]) = {
      if (m.runtimeClass.isPrimitive)
        m.runtimeClass.getName.capitalize
      else m.runtimeClass.getName
    }
    val typename = capitalized(manifest)
    val types = if (manifest.typeArguments.isEmpty) "" else {
      s"[${manifest.typeArguments.map(m => typeName(m)).mkString(",")}]"
    }
    (typename + types).replace("$",".") // replace hack, better solution?
  }
  def key[T : Manifest](k: SettingKey[T]): String = {
    s"""SettingKey[${typeName[T]}]("${k.key.label}")""" + config(k.scope)
  }
  def key[T : Manifest](k: TaskKey[T]): String = {
    s"""TaskKey[${typeName[T]}]("${k.key.label}")""" + config(k.scope)
  }
}

object GradleBuildSerializer {
  import Serializer._
  case class SbtProject(id: String, base: File, isApplication: Boolean, dependencies: Set[String], settings: Seq[SbtSetting]) {
    override def toString = s"SbtProject(id=$id, base=$base, dependencies=$dependencies"
    def escaped(s: String) = {
      val needEscape = s.zipWithIndex exists { case (c, i) =>
        (i == 0 && !Character.isJavaIdentifierStart(c)) || (i != 0 && !Character.isJavaIdentifierPart(c))
      }
      if (needEscape) {
        s"`$s`"
      } else s
    }
    lazy val project = Project(base = base, id = id).settings(
      (if (isApplication) Plugin.androidBuild else Plugin.androidBuildAar): _*).settings(
        settings.map(_.setting):_*)
    lazy val dependsOnProjects = {
      if (dependencies.nonEmpty) ".dependsOn(" + dependencies.map(escaped).mkString(",") + ")" else ""
    }
    lazy val dependsOnSettings = {
      if (dependencies.nonEmpty) {
        val depSettings = dependencies map { d =>
          s"""
           |  TaskKey[Seq[android.Dependencies.LibraryDependency]]("transitive-aars") in Android <++=
           |    TaskKey[Seq[android.Dependencies.LibraryDependency]]("aars") in Android in ${escaped(d)},
           |  collectResources in Android <<=
           |    collectResources in Android dependsOn (compile in Compile in ${escaped(d)}),
           |  compile in Compile <<= compile in Compile dependsOn(
           |    sbt.Keys.`package` in Compile in ${escaped(d)}),
           |  localProjects in Android += LibraryProject(${escaped(d)}.base)
           |""".
            stripMargin
          } mkString ",\n"
        s".settings($depSettings)"
      } else ""
    }
    lazy val serialized =
      s"""
         |val ${escaped(id)} = Project(id = ${enc(id)}, base = ${enc(base)}).settings(
         |  ${if (isApplication) "android.Plugin.androidBuild" else "android.Plugin.androidBuildAar"}:_*).settings(
         |    ${settings.map(_.serialized).mkString(",\n    ")}
         |)$dependsOnProjects$dependsOnSettings
       """.stripMargin
  }

  def toposort(ps: List[SbtProject]): List[SbtProject] = {
    val projectMap = ps.map(p => (p.id.replace(":", ""), p)).toMap
    Dag.topologicalSort(ps)(_.dependencies flatMap projectMap.get)
  }

  import language.existentials
  case class SbtSetting(serialized: String, setting: Def.Setting[_])
  object Op {
    case object := extends Op {
      val serialized = ":="
      def apply[T : Encoder : Manifest](lhs: SettingKey[T], rhs: T) = SbtSetting(serialize(lhs, serialized, rhs), lhs := rhs)
      def apply[T : Encoder : Manifest](lhs: TaskKey[T], rhs: T) = SbtSetting(serialize(lhs, serialized, rhs), lhs := rhs)
    }
    case object += extends Op {
      val serialized = "+="
      def apply[T : Manifest,U : Encoder](lhs: SettingKey[T], rhs: U)(implicit m: Append.Value[T,U]) = SbtSetting(serialize(lhs, serialized, rhs), lhs += rhs)
      def apply[T : Manifest,U : Encoder](lhs: TaskKey[T], rhs: U)(implicit m: Append.Value[T,U]) = SbtSetting(serialize(lhs, serialized, rhs), lhs += rhs)
    }
    case object ++= extends Op {
      val serialized = "++="
      def apply[T : Manifest, U : Encoder](lhs: SettingKey[T], rhs: U)(implicit m: Append.Values[T,U]) = SbtSetting(serialize(lhs, serialized, rhs), lhs ++= rhs)
      def apply[T : Manifest, U : Encoder](lhs: TaskKey[T], rhs: U)(implicit m: Append.Values[T,U]) = SbtSetting(serialize(lhs, serialized, rhs), lhs ++= rhs)
    }
  }

  implicit class SerializableSettingKey[T : Encoder : Manifest](val k: SettingKey[T]) {
    def /:=(t: T) = Op := (k, t)
    def /+=[U : Encoder](u: U)(implicit m: Append.Value[T,U]) = Op += (k, u)
    def /++=[U : Encoder](u: U)(implicit m: Append.Values[T,U]) = Op ++= (k, u)
  }
  implicit class SerializableTaskKey[T : Encoder : Manifest](val k: TaskKey[T]) {
    def /:=(t: T) = Op := (k, t)
    def /+=[U : Encoder](u: U)(implicit m: Append.Value[T,U]) = Op += (k, u)
    def /++=[U : Encoder](u: U)(implicit m: Append.Values[T,U]) = Op ++= (k, u)
  }
}
