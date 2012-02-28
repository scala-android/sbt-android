import sbt._
import sbt.Keys._

import scala.io.Source
import scala.collection.JavaConversions._

import java.util.Properties
import java.io.{File,FilenameFilter,FileInputStream}

import com.android.sdklib.{IAndroidTarget,SdkConstants}
import com.android.sdklib.build.ApkBuilder

import proguard.{Configuration => PgConfig, ProGuard, ConfigurationParser}

import AndroidKeys._

object AndroidTasks {
  var createDebug = true

  def using[A <: { def close() },B](closeable: => A)(f: A => B): Option[B] = {
    var c: Option[A] = None
    try {
      c = Option(closeable)
      c map f
    } finally
      c foreach(_.close)
  }

  val packageResourcesOptionsTaskDef = ( manifestPath
                                       , versionCode
                                       , versionName
                                       , baseDirectory
                                       , binPath
                                       , libraryProjects
                                       , platformJar
                                       ) map {
    (m, v, n, b, bin, l, j) =>
    val vc = v getOrElse sys.error("versionCode is not set")
    val vn = n getOrElse sys.error("versionName is not set")

    // crunched path needs to go before uncrunched
    val libraryResources = for {
      r <- l
      arg <- Seq("-S", (findLibraryBinPath(b / r) / "res").getCanonicalPath,
        "-S", (b / r / "res").getCanonicalPath)
    } yield arg

    val assets = (b / "assets")
    val assetArgs = if (assets.exists) Seq("-A", assets.getCanonicalPath)
      else Seq.empty

    val libraryAssets = for {
      d <- l collect { case r if (b / r / "assets").exists() =>
        (b/r/"assets").getCanonicalPath }
      arg <- Seq("-A", d)
    } yield arg

    val debug = if (createDebug) Seq("--debug-mode") else Seq.empty
    Seq("package", "-f",
      // only required if refs lib projects, doesn't hurt otherwise?
      "--auto-add-overlay",
      "-M", m.absolutePath, // manifest
      "-S", (bin / "res").absolutePath, // crunched png path
      "-S", (b / "res").absolutePath, // resource path
      "--generate-dependencies", // generate .d file
      "-I", j,
      "--no-crunch"
      ) ++ libraryResources ++ assetArgs ++ libraryAssets ++ debug
  }

  val packageResourcesTaskDef = ( aaptPath
                                , packageResourcesOptions
                                , baseDirectory
                                , binPath
                                , streams
                                ) map {
    (a, o, b, bin, s) =>
    val rel = if (createDebug) "-debug" else "-release"
    val basename = "resources" + rel + ".ap_"
    val dfile = bin * (basename + ".d") get
    val p = bin / basename

    if (dfile.size == 0 || outofdate(dfile(0))) {
      val cmd = a +: (o ++ Seq("-F", p.getAbsolutePath))

      s.log.debug("aapt: " + cmd.mkString(" "))

      val r = cmd !

      if (r != 0) {
        sys.error("failed")
      }
    } else s.log.info(p.getName + " is up-to-date")
    p
  }

  // TODO this fails when new files are added but none are modified
  private def outofdate(dfile: File): Boolean = {
    (using (Source.fromFile(dfile)) { s =>
      val dependencies = (s.getLines.foldLeft(
        (Map[String,Seq[String]](), Option[String](null))) {
          case ((deps, dep), line) =>
          val d = if (dep.isEmpty) {
            Option(line.init.trim)
          } else if (!line.endsWith("\\")) {
            None
          } else dep

        ((dep map { n =>
          val l = line.stripSuffix("\\").trim.stripPrefix(":").trim
          deps + (n -> ((deps.get(n) getOrElse Seq.empty) :+ l))
        } getOrElse deps), d)
      })._1

      dependencies.exists { case (d,l) =>
        val f = new File(d)
        l.exists { i => new File(i).lastModified > f.lastModified }
      }
    }) getOrElse false
  }

  val apkbuildTaskDef = ( name
                        , packageResources
                        , dex
                        , baseDirectory
                        , binPath
                        , libraryProjects
                        , unmanagedBase
                        , unmanagedJars in Compile
                        , managedClasspath in Compile
                        ) map {
    (n, r, d, base, b, p, l, u, m) =>
    val rel = if (createDebug) "-debug-unaligned.apk"
      else "-release-unsigned.apk"
    val pkg = n + rel
    val output = b / pkg

    val builder = new ApkBuilder(output, r, d,
      if (createDebug) ApkBuilder.getDebugKeystore else null, null)

    builder.setDebugMode(createDebug)
    (m ++ u) foreach { j => builder.addResourcesFromJar(j.data) }

    ((for {
      path <- p
      lib <- ApkBuilder.getNativeFiles(base / path / "libs", createDebug)
    } yield lib) ++ ApkBuilder.getNativeFiles(l, createDebug)) foreach {
        f => builder.addNativeLibraries(f.mFile)
    }

    builder.sealApk()

    output
  }

  val signReleaseTaskDef = (properties, apkbuild, streams) map {
    (p, a, s) =>
    val bin = a.getParentFile
    if (createDebug) {
      s.log.info("Debug package does not need signing: " + a.getName)
      a
    } else {
      (Option(p.getProperty("key.alias")),
        Option(p.getProperty("key.store")),
        Option(p.getProperty("key.store.password"))) match {

        case (Some(alias),Some(store),Some(passwd)) =>
        import SignJar._
        val t = Option(p.getProperty("key.store.type")) getOrElse "jks"
        val signed = bin / a.getName.replace("-unsigned", "-unaligned")
        val options = Seq( storeType(t)
                         , storePassword(passwd)
                         , signedJar(signed)
                         , keyStore(file(store).toURI.toURL)
                         )
        sign(a, alias, options) { (jarsigner, args) => (jarsigner +: args) !  }

        s.log.info("Signed: " + signed.getName)
        signed
        case _ =>
        s.log.warn("Package needs signing: " + a.getName)
        a
      }
    }
  }

  val zipalignTaskDef = (zipalignPath, signRelease, streams) map {
    (z, r, s) =>
    if (r.getName.contains("-unsigned")) {
      s.log.warn("Package needs signing and zipaligning: " + r.getName)
      r
    } else {
      val bin = r.getParentFile
      val aligned = bin / r.getName.replace("-unaligned", "")

      val rv = Seq(z, "-f", "4", r.getAbsolutePath, aligned.getAbsolutePath) !

      if (rv != 0) {
        sys.error("failed")
      }

      s.log.info("zipaligned: " + aligned.getName)
      aligned
    }
  }

  val renderscriptTaskDef = ( sdkPath
                            , genPath
                            ) map { (s, g) =>
    import SdkConstants._

    val rs        = s + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_RENDERSCRIPT
    val rsInclude = s + OS_SDK_PLATFORM_TOOLS_FOLDER + OS_FRAMEWORK_RS
    val rsClang   = s + OS_SDK_PLATFORM_TOOLS_FOLDER + OS_FRAMEWORK_RS_CLANG
    Seq[File]()
  }

  val aidlTaskDef = ( sdkPath
                    , genPath
                    , platform
                    ) map { (s, g, p) =>
    import SdkConstants._
    val aidl          = s + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_AIDL
    val frameworkAidl = p.getPath(IAndroidTarget.ANDROID_AIDL)

    Seq[File]()
  }

  val aaptGeneratorOptionsTaskDef = ( manifestPath
                                    , baseDirectory
                                    , packageName
                                    , libraryProjects
                                    , platformJar
                                    , genPath
                                    ) map {
    (m, b, p, l, j, g) =>

    val libraryResources = for {
      r <- l
      arg <- Seq("-S", (b / r / "res").getCanonicalPath)
    } yield arg

    Seq("package",
      // only required if refs lib projects, doesn't hurt otherwise?
      "--auto-add-overlay",
      "-m", // make package directories in gen
      "--generate-dependencies", // generate R.java.d
      "--custom-package", p, // package name
      "-M", m.absolutePath, // manifest
      "-S", (b / "res").absolutePath, // resource path
      "-I", j, // platform jar
      "-J", g.absolutePath) ++ libraryResources
  }

  val aaptGeneratorTaskDef = ( aaptPath
                             , aaptGeneratorOptions
                             , genPath
                             , streams
                             ) map {
    (a, o, g, s) =>
    g.mkdirs()

    val dfile = (g ** "R.java.d" get)
    if (dfile.size == 0 || dfile.exists(f => outofdate(f))) {
      val r = (a +: o) !

      if (r != 0) {
        sys.error("failed")
      }
    } else s.log.info("R.java is up-to-date")
    (g ** "R.java" get) ++ (g ** "Manifest.java" get)
  }

  val pngCrunchTaskDef = (aaptPath, binPath, baseDirectory) map {
    (a, bin, base) =>
    val res = bin / "res"
    res.mkdirs()
    Seq(a, "crunch", "-v",
      "-S", (base / "res").absolutePath,
      "-C", res.absolutePath) !

    ()
  }

  def proguardConfigDef = {
    using(AndroidSdkPlugin.getClass.getClassLoader.getResourceAsStream(
      "android-proguard.config")) { in =>
        Seq(Source.fromInputStream(in).getLines.toSeq: _*)
    } getOrElse Seq[String]()
  }

  val dexTaskDef = ( dexPath
                   , proguard
                   , classesDex
                   , managedClasspath in Compile
                   , unmanagedJars in Compile
                   , streams) map {
    (d, p, c, m, u, s) =>
    val inputs = p map { f => Seq(f) } getOrElse {
      (m ++ u) collect {
        // no proguard? then we don't need to dex scala!
        case x if !x.data.getName.startsWith("scala-library") => x.data
      }
    }
    if (inputs.exists { _.lastModified > c.lastModified }) {
      s.log.info("dexing input")
      // TODO maybe split out options into a separate task?
      val r = Seq(d, "--dex",
        // TODO support instrumented builds
        // --no-locals if instrumented
        // --verbose
        "--num-threads",
        "" + java.lang.Runtime.getRuntime.availableProcessors,
        "--output", c.getAbsolutePath
        ) ++ (inputs map { _.getAbsolutePath }) !

      if (r != 0) {
        sys.error("failed")
      }
    } else {
      s.log.info(c.getName + " is up-to-date")
    }

    c
  }

  val proguardInputsTaskDef = ( proguardScala
                              , proguardLibraries
                              , proguardExcludes
                              , managedClasspath in Compile
                              , unmanagedClasspath in Compile
                              , platformJar
                              , binPath
                              ) map {
    (s, l, e, m, u, p, b) =>

    // TODO remove duplicate jars
    val injars = (((m ++ u) map { _.data }) :+ (b/"classes.jar")) filter {
      in =>
      (s || !in.getName.startsWith("scala-library")) &&
        !l.exists { i => i.getName == in.getName}
    }

    (injars,file(p) +: l)
  }

  val proguardTaskDef: Project.Initialize[Task[Option[File]]] =
      ( useProguard
      , proguardConfig
      , proguardOptions
      , libraryProject
      , binPath in Android
      , proguardInputs
      , streams
      ) map {
    case (p, c, o, l, b, (jars, libjars), s) =>
    if (p && !l) {
      val t = b / "classes.proguard.jar"
      if ((jars ++ libjars).exists { _.lastModified > t.lastModified }) {
        val injars = "-injars " + (jars map {
          _.getPath + "(!META-INF/**)" } mkString(File.pathSeparator))
        val libraryjars = for {
          j <- libjars
          a <- Seq("-libraryjars", j.getAbsolutePath)
        } yield a
        val outjars = "-outjars " + t.getAbsolutePath
        val printmappings = Seq("-printmapping",
          (b / "mappings.txt").getAbsolutePath)
        val cfg = c ++ o ++ libraryjars ++ printmappings :+ injars :+ outjars
        val config = new PgConfig
        new ConfigurationParser(cfg.toArray[String]).parse(config)
        new ProGuard(config).execute
      } else {
        s.log.info(t.getName + " is up-to-date")
      }
      Option(t)
    } else None
  }

  private def findLibraryBinPath(path: File) = {
    val props = loadProperties(path)
    directoriesList("out.dir", "bin", props, path)(0)
  }

  def loadProperties(path: File): Properties = {
    val p = new Properties
    (path * "*.properties" get) foreach { f =>
      using(new FileInputStream(f)) { in => p.load(in) }
    }
    p
  }

  private def directoriesList(prop: String, default: String,
      props: Properties, base: File) =
    Option(props.getProperty(prop)) map { s =>
      (s.split(":") map { base / _ }).toSeq
    } getOrElse Seq(base / default)

  def setDirectories(prop: String, default: String) = (
      baseDirectory, properties in Android) {
    (base, props) => directoriesList(prop, default, props, base)
  }

  def setDirectory(prop: String, default: String) = (
      baseDirectory, properties in Android) {
    (base, props) => directoriesList(prop, default, props, base).get(0)
  }

  val unmanagedJarsTaskDef = ( unmanagedJars
                             , baseDirectory
                             , libraryProjects in Android, streams) map {
    (u, b, l, s) =>

    // remove scala-library if present
    // add all dependent library projects' classes.jar files
    (u ++ (l map { p => Attributed.blank((findLibraryBinPath(b / p) /
      "classes.jar").getCanonicalFile)
    }) ++ (for {
        d <- l
        j <- (b / d / "libs") * "*.jar" get
      } yield Attributed.blank(j.getCanonicalFile))
    ) filter { !_.data.getName.startsWith("scala-library") }
  }
}
