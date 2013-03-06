import sbt._
import sbt.Keys._
import classpath.ClasspathUtilities

import scala.io.Source
import scala.collection.JavaConversions._
import scala.util.control.Exception._
import scala.xml.{XML, Elem}

import java.util.Properties
import java.io.{File,FilenameFilter,FileInputStream}

import com.android.ddmlib.{IDevice, IShellOutputReceiver}
import com.android.sdklib.{IAndroidTarget,SdkConstants}
import com.android.sdklib.build.ApkBuilder
import com.android.sdklib.internal.build.BuildConfigGenerator

import proguard.{Configuration => PgConfig, ProGuard, ConfigurationParser}

import AndroidKeys._

object AndroidTasks {
  val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  // wish this could be protected
  var _createDebug = true

  def createDebug = _createDebug
  private def createDebug_=(d: Boolean) = _createDebug = d

  def resourceUrl =
    AndroidSdkPlugin.getClass.getClassLoader.getResource _

  val buildConfigGeneratorTaskDef = ( genPath
                                    , packageName
                                    ) map {
    (g, p) =>
    val generator = new BuildConfigGenerator(g.getAbsolutePath, p, createDebug)
    generator.generate()
    g ** "BuildConfig.java" get
  }
  val typedResourcesGeneratorTaskDef = ( typedResources
                                       , aaptGenerator
                                       , packageName
                                       , resourceDirectory in Compile
                                       , platformJars
                                       , baseDirectory
                                       , libraryProjects
                                       , genPath
                                       , streams
                                       ) map {
    case (t, a, p, r, (j, x), b, l, g, s) =>
    val tr = p.split("\\.").foldLeft (g) { _ / _ } / "TR.scala"
    if (!t || !a.exists { _.lastModified > tr.lastModified })
      Seq.empty[File]
    else {
      val androidjar = ClasspathUtilities.toLoader(file(j))
      val layouts = (r ** "layout*" ** "*.xml" get) ++
        (for {
          lib <- l
          xml <- (b / lib) ** "layout*" ** "*.xml" get
        } yield xml)

      // XXX handle package references? @id/android:ID or @id:android/ID
      val re = "@\\+id/(.*)".r

      def classForLabel(l: String) = {
        if (l contains ".") Some(l)
        else {
          Seq("android.widget.", "android.view.", "android.webkit.").flatMap {
            pkg =>
            catching(classOf[ClassNotFoundException]) opt {
              androidjar.loadClass(pkg + l).getName
            }
          }.headOption
        }
      }

      def warn(res: Seq[(String,String)]) = {
        // TODO merge to a common ancestor
        //   To View for views or ViewGroup for layouts
        val overrides = res.groupBy(r => r._1) filter (
          _._2.toSet.size > 1) collect {
          case (k,v) =>
            s.log.warn("%s was reassigned: %s" format (k,
              v map (_._2) mkString " => "))
            k -> (if (v endsWith "Layout")
              "android.view.ViewGroup" else "android.view.View")
        }

        (res ++ overrides).toMap
      }
      val layoutTypes = warn(for {
        file   <- layouts
        layout  = XML loadFile file
        l      <- classForLabel(layout.label)
      } yield file.getName.stripSuffix(".xml") -> l)

      val resources = warn(for {
        b      <- layouts
        layout  = XML loadFile b
        n      <- layout.descendant_or_self
        re(id) <- n.attribute(ANDROID_NS, "id") map { _.head.text }
        l      <- classForLabel(n.label)
      } yield id -> l)

      val trTemplate = IO.readLinesURL(
        resourceUrl("tr.scala.template")) mkString "\n"

      tr.delete()
      IO.write(tr, trTemplate format (p,
        resources map { case (k,v) =>
          "  val `%s` = TypedResource[%s](R.id.`%s`)" format (k,v,k)
        } mkString "\n",
        layoutTypes map { case (k,v) =>
          "    val `%s` = TypedLayout[%s](R.layout.`%s`)" format (k,v,k)
        } mkString "\n"))
      Seq(tr)
    }
  }

  val packageResourcesOptionsTaskDef = ( manifestPath
                                       , versionCode
                                       , versionName
                                       , baseDirectory
                                       , binPath
                                       , libraryProjects
                                       , platformJars
                                       ) map {
    case (m, v, n, b, bin, l, (j, x)) =>
    //val vc = v getOrElse sys.error("versionCode is not set")
    //val vn = n getOrElse sys.error("versionName is not set")

    // crunched path needs to go before uncrunched
    val resources = Seq(
      "-S", (bin / "res").absolutePath, // crunched png path
      "-S", (b / "res").absolutePath // resource path
    ) ++ (for {
      r      <- l
      binPath = (findLibraryBinPath(b / r) / "res")
      arg <- (if (binPath.exists()) Seq("-S", (binPath.getCanonicalPath)) else
        Seq.empty[String]) ++ Seq("-S", (b / r / "res").getCanonicalPath)
    } yield arg)

    val assets = (b / "assets")
    val assetArgs = if (assets.exists) Seq("-A", assets.getCanonicalPath)
      else Seq.empty

    val libraryAssets = for {
      d <- l collect { case r if (b / r / "assets").exists =>
        (b/r/"assets").getCanonicalPath }
      arg <- Seq("-A", d)
    } yield arg

    val debug = if (createDebug) Seq("--debug-mode") else Seq.empty
    Seq("package", "-f",
      // only required if refs lib projects, doesn't hurt otherwise?
      "--auto-add-overlay",
      "-M", m.absolutePath, // manifest
      "--generate-dependencies", // generate .d file
      "-I", j,
      "-G", (bin / "proguard.txt").absolutePath,
      "--no-crunch"
      ) ++ resources ++ assetArgs ++ libraryAssets ++ debug
  }

  val cleanAaptTaskDef = ( binPath, genPath ) map { (b,g) =>
    val rel = if (createDebug) "-debug" else "-release"
    val basename = "resources" + rel + ".ap_"
    val p = b / basename
    p.delete()
    IO.delete(g)
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

    if (dfile.isEmpty || (dfile exists outofdate)) {
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
    val (targets, dependencies, _) = IO.readLines(dfile).foldLeft(
      (Seq.empty[String],Seq.empty[String],false)) {
      case ((target, dep, doingDeps), line) =>
      val l = line.stripSuffix("\\").trim
      val l2 = l.stripPrefix(":").trim
      if (l == l2 && !doingDeps) {
        (target :+ l,dep,doingDeps)
      } else if (l == l2 && doingDeps) {
        (target,dep :+ l,doingDeps)
      } else if (l != l2) {
        (target,dep :+ l2,true)
      } else {
        sys.error("unexpected state: " + l)
      }
    }

    val dependencyFiles = dependencies map { d => new File(d) }
    targets exists { t =>
      val target = new File(t)
      dependencyFiles exists { _.lastModified > target.lastModified }
    }
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

    val nativeLibraries = (p map { z => findLibraryLibPath(base / z) } collect {
      case f if f.exists => f
    } map {
        ApkBuilder.getNativeFiles(_, createDebug)
    } flatten) ++ (
      if (l.exists) ApkBuilder.getNativeFiles(l, createDebug) else Seq.empty)

    builder.addNativeLibraries(nativeLibraries)
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
        sign(a, alias, options) { (jarsigner, args) =>
          (jarsigner +: (args ++ Seq(
            "-digestalg", "SHA1", "-sigalg", "MD5withRSA"))) !
        }

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
                            , binPath
                            , genPath
                            , targetSdkVersion
                            , unmanagedSourceDirectories in Compile
                            , streams
                            ) map { (s, b, g, t, u, l) =>
    import SdkConstants._

    val rs        = s + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_RENDERSCRIPT
    val rsInclude = s + OS_SDK_PLATFORM_TOOLS_FOLDER + OS_FRAMEWORK_RS
    val rsClang   = s + OS_SDK_PLATFORM_TOOLS_FOLDER + OS_FRAMEWORK_RS_CLANG

    val scripts = for {
      src    <- u
      script <- (src ** "*.rs" get)
    } yield (src,script)

    val generated = g ** "*.java" get

    val target = if (t < 11) "11" else t.toString

    scripts flatMap { case (src, script) =>

      // optimization level -O also requires sdk r17
      // debug requires sdk r17
      //val debug = if (createDebug) Seq("-g") else Seq.empty

      val cmd = Seq(rs, "-I", rsInclude, "-I", rsClang,
        "-target-api", target,
        "-d", (g /
          (script relativeTo src).get.getParentFile.getPath).getAbsolutePath,
        //"-O", level, 0 through 3
        "-MD", "-p", g.getAbsolutePath,
        "-o", (b / "res" / "raw").getAbsolutePath) :+ script.getAbsolutePath

      val r = cmd !

      if (r != 0)
        sys.error("renderscript failed: " + r)

      (g ** (script.getName.stripSuffix(".rs") + ".d") get) flatMap { dfile =>
        val lines = IO.readLines(dfile)
        // ugly, how do I make this prettier
        lines zip lines.tail takeWhile (!_._1.endsWith(": \\")) flatMap {
          case (line, next) =>
          val path  = line.stripSuffix("\\").trim.stripSuffix(":")
          val path2 = next.stripSuffix("\\").trim.stripSuffix(":")
          (if (path.endsWith(".java")) Some(new File(path)) else None) ++
            (if (path2.endsWith(".java")) Some(new File(path2)) else None)
        }
      } toSet
    }
  }

  val aidlTaskDef = ( sdkPath
                    , genPath
                    , platform
                    , unmanagedSourceDirectories in Compile
                    , streams
                    ) map { (s, g, p, u, l) =>
    import SdkConstants._
    val aidl          = s + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_AIDL
    val frameworkAidl = p.getPath(IAndroidTarget.ANDROID_AIDL)
    val aidls = for {
      src <- u
      idl <- (src ** "*.aidl" get)
    } yield idl

    aidls flatMap { idl =>
      val out = g ** (idl.getName.stripSuffix(".aidl") + ".java") get
      val cmd = Seq(aidl,
        "-p" + frameworkAidl,
        "-o" + g.getAbsolutePath,
        "-a") ++ (u map {
          "-I" + _.getAbsolutePath
        }) :+ idl.getAbsolutePath

      // TODO FIXME this doesn't account for other dependencies
      if (out.isEmpty || (out exists { idl.lastModified > _.lastModified })) {
        val r = cmd !

        if (r != 0)
          sys.error("aidl failed")

        g ** (idl.getName.stripSuffix(".aidl") + ".java") get
      } else out
    }
  }

  private def makeAaptOptions(manifest: File, base: File, bin: File,
      packageName: String, isLib: Boolean, libraries: Seq[String],
      androidjar: String, gen: File) = {

    val nonConstantId = if (isLib) Seq("--non-constant-id") else Seq.empty
    val libraryResources = for {
      r <- libraries
      rbin = findLibraryBinPath(base / r)
      rbinres = rbin / "res"
      arg <- (if (rbinres.exists)
        Seq("-S", rbinres.getCanonicalPath) else Seq.empty) ++
          Seq("-S", (base / r / "res").getCanonicalPath)
    } yield arg

    val res = (if ((bin / "res").exists)
      Seq("-S", (bin / "res").absolutePath) // bin res
    else Seq.empty) ++
      Seq("-S", (base / "res").absolutePath) // resource path

    Seq("package",
      // only required if refs lib projects, doesn't hurt otherwise?
      "--auto-add-overlay",
      "-m", // make package directories in gen
      "--generate-dependencies", // generate R.java.d
      "-M", manifest.absolutePath, // manifest
      "-I", androidjar, // platform jar
      "-J", gen.absolutePath) ++ res ++ libraryResources ++ nonConstantId
  }

  val aaptGeneratorOptionsTaskDef = ( manifestPath
                                    , baseDirectory
                                    , binPath
                                    , packageName
                                    , aaptNonConstantId
                                    , libraryProject
                                    , libraryProjects
                                    , platformJars
                                    , genPath
                                    ) map {
    case (m, b, bin, p, n, i, l, (j, x), g) =>
    makeAaptOptions(m, b, bin, p, i && n, l, j, g)
  }

  val aaptGeneratorTaskDef = ( aaptPath
                             , aaptGeneratorOptions
                             , aaptNonConstantId
                             , genPath
                             , libraryProjects
                             , baseDirectory
                             , platformJars
                             , streams
                             ) map {
    case (a, o, n, g, l, b, (j, x), s) =>
    g.mkdirs()

    val dfile = g * "R.java.d" get

    if (dfile.isEmpty || (dfile exists outofdate)) {

      val libPkgs = l map { lib =>
        val base = b / lib
        val manifest = base / "AndroidManifest.xml"

        val m = XML.loadFile(manifest)
        m.attribute("package") get (0) text
      }

      // prefix with ":" to match ant scripts
      s.log.debug("lib packages: " + libPkgs)
      val extras = if (libPkgs.isEmpty) Seq.empty
        else Seq("--extra-packages", ":" + (libPkgs mkString ":"))

      s.log.debug("aapt: " + (a +: (o ++ extras)).mkString(" "))
      val r = (a +: (o ++ extras)) !

      if (r != 0)
        sys.error("failed")
      else
        s.log.info("Generated R.java")
    } else
      s.log.info("R.java is up-to-date")
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

  val proguardConfigTaskDef = ( baseDirectory
                              , binPath
                              , sdkPath
                              , useSdkProguard) map {
    (base,bin,p,u) =>
    if (!u)
      IO.readLinesURL(resourceUrl("android-proguard.config")).toSeq
    else {
      import SdkConstants._
      import File.{separator => S}
      val c1 = file(p + OS_SDK_TOOLS_FOLDER + FD_PROGUARD + S +
        FN_ANDROID_PROGUARD_FILE)

      val c2 = base / "proguard-project.txt"
      val c3 = bin / "proguard.txt"
      (IO.readLines(c1) ++ (if (c2.exists) IO.readLines(c2) else Seq.empty) ++
        (if (c3.exists) IO.readLines(c3) else Seq.empty)).toSeq: Seq[String]
    }
  }

  def dedupeClasses(bin: File, jars: Seq[File]): Seq[File] = {
      // only attempt to dedupe if jars with the same name are found
      if (jars.groupBy(_.getName) exists { case (k,v) => v.size > 1 }) {
        val combined = bin / "all-classes.jar"
        val combined_tmp = bin / "all_tmp"
        if (jars exists (_.lastModified > combined.lastModified)) {
          IO.createDirectory(combined_tmp)
          val files = jars.foldLeft (Set.empty[File]) { (acc,j) =>
            acc ++ IO.unzip(j, combined_tmp, { n: String =>
              !n.startsWith("META-INF") })
          }
          IO.jar(files map { f => (f,IO.relativize(combined_tmp, f).get) },
            combined, new java.util.jar.Manifest)
        }
        IO.delete(combined_tmp)
        combined :: Nil
      } else jars
  }

  val dexInputsTaskDef = ( proguard
                         , binPath
                         , managedClasspath in Compile
                         , dependencyClasspath in Compile
                         , unmanagedJars in Compile
                         , classesJar
                         , streams) map {
    (p, b, m, d, u, j, s) =>

    dedupeClasses(b, p map { f => Seq(f) } getOrElse {
      ((m ++ u ++ d) collect {
        // no proguard? then we don't need to dex scala!
        case x if !x.data.getName.startsWith("scala-library") &&
          x.data.getName.endsWith(".jar") => x.data.getCanonicalFile
      })
    } :+ j)
  }

  val dexTaskDef = ( dexPath
                   , dexInputs
                   , libraryProject
                   , classesDex
                   , streams) map {
    (d, i, l, c, s) =>
    if (l) sys.error("Cannot dex a library project")

    if (i.exists { _.lastModified > c.lastModified }) {
      s.log.info("dexing input")
      // TODO maybe split out options into a separate task?
      val cmd = Seq(d, "--dex",
        // TODO support instrumented builds
        // --no-locals if instrumented
        // --verbose
        "--num-threads",
        "" + java.lang.Runtime.getRuntime.availableProcessors,
        "--output", c.getAbsolutePath
        ) ++ (i map { _.getAbsolutePath })

      s.log.debug("dex: " + cmd.mkString(" "))

      val r = cmd !

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
                              , dependencyClasspath in Compile
                              , platformJars
                              , classesJar
                              , binPath
                              ) map {
    case (s, l, e, m, u, d, (p, x), c, b) =>

    // TODO remove duplicate jars
    val injars = dedupeClasses(b, ((((m ++ u ++ d) map {
      _.data.getCanonicalFile }) :+ c) filter {
        in =>
        (s || !in.getName.startsWith("scala-library")) &&
          !l.exists { i => i.getName == in.getName}
      }))

    val extras = x map (f => file(f))
    (injars,file(p) +: (extras ++ l))
  }

  val proguardTaskDef: Project.Initialize[Task[Option[File]]] =
      ( useProguard
      , useProguardInDebug
      , proguardConfig
      , proguardOptions
      , libraryProject
      , binPath in Android
      , proguardInputs
      , streams
      ) map { case (p, d, c, o, l, b, (jars, libjars), s) =>
    if ((p && !createDebug && !l) || ((d && createDebug) && !l)) {
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
        cfg foreach (l => s.log.debug(l))
        val config = new PgConfig
        val cpclass = classOf[ConfigurationParser]
        import java.util.Properties
        // support proguard 4.8+ in the getOrElse case
        // if the api changes again, it will be a runtime error
        val parser: ConfigurationParser =
          catching(classOf[NoSuchMethodException]) opt {
            val ctor = cpclass.getConstructor(classOf[Array[String]])
            ctor.newInstance(cfg.toArray[String])
          } getOrElse {
            val ctor = cpclass.getConstructor(classOf[Array[String]],
              classOf[Properties])
            ctor.newInstance(cfg.toArray[String], new Properties)
          }
        parser.parse(config)
        new ProGuard(config).execute
      } else {
        s.log.info(t.getName + " is up-to-date")
      }
      Option(t)
    } else None
  }

  private def targetDevice(path: String, log: Logger): Option[IDevice] = {
    AndroidCommands.initAdb

    val devices = AndroidCommands.deviceList(Some(path), log)
    if (devices.isEmpty) {
      sys.error("no devices connected")
    } else {
      AndroidCommands.defaultDevice flatMap { device =>
        devices find (device == _.getSerialNumber) orElse {
          log.warn("default device not found, falling back to first device")
          None
        }
      } orElse {
        Some(devices(0))
      }
    }
  }
  def runTaskDef(install: TaskKey[Unit],
      sdkPath: SettingKey[String],
      manifest: SettingKey[Elem],
      packageName: SettingKey[String]) = inputTask { result =>
    (install, sdkPath, manifest, packageName, result, streams) map {
      (_, k, m, p, r, s) =>

      // if an arg is specified, try to launch that
      (if (r.isEmpty) None else Some(r(0))) orElse ((m \\ "activity") find {
        // runs the first-found activity
        a => (a \ "intent-filter") exists { filter =>
          val attrpath = "@{%s}name" format ANDROID_NS
          (filter \\ attrpath) exists (_.text == "android.intent.action.MAIN")
        }
      } map { activity =>
        val name = activity.attribute(ANDROID_NS, "name") get (0) text

        "%s/%s" format (p, if (name.indexOf(".") == -1) "." + name else name)
      }) map { intent =>
        val receiver = new IShellOutputReceiver() {
          val b = new StringBuilder
          override def addOutput(data: Array[Byte], off: Int, len: Int) =
            b.append(new String(data, off, len))
          override def flush() {
            s.log.info(b.toString)
            b.clear
          }
          override def isCancelled = false
        }
        targetDevice(k, s.log) map { d =>
          val command = "am start -n %s" format intent
          s.log.debug("Executing [%s]" format command)
          d.executeShellCommand(command, receiver)

          if (receiver.b.toString.length > 0)
            s.log.info(receiver.b.toString)

          s.log.debug("run command executed")
        }
      } getOrElse {
        sys.error(
          "No activity found with action 'android.intent.action.MAIN'")
      }

      ()
    }
  }

  val installTaskDef = (packageT, libraryProject, sdkPath, streams) map {
    (p, l, k, s) =>

    if (!l) {
      targetDevice(k, s.log) foreach { d =>
        Option(d.installPackage(p.getAbsolutePath, true)) map { err =>
          sys.error("Install failed: " + err)
        } getOrElse {
          s.log.info("Install successful")
        }
      }
    }
  }

  val uninstallTaskDef = (sdkPath, packageName, streams) map { (k,p,s) =>
    targetDevice(k, s.log) foreach { d =>
      Option(d.uninstallPackage(p)) map { err =>
        sys.error("Uninstall failed: " + err)
      } getOrElse {
        s.log.info("Uninstall successful")
      }
    }
  }

  private def findLibraryBinPath(path: File) = {
    val props = loadProperties(path)
    directoriesList("out.dir", "bin", props, path)(0)
  }

  // any way to get rid of the second path / "libs" ?
  private def findLibraryLibPath(path: File) =
    Seq("libs", "lib") map { path / _ } find { _.exists } getOrElse (
      path / "libs")

  def loadLibraryReferences(b: File, p: Properties, prefix: String = ""):
  Seq[String] = {
      p.stringPropertyNames.collect {
        case k if k.startsWith("android.library.reference") => k
      }.toList.sortWith { (a,b) => a < b } map { k =>
        p(k) +: loadLibraryReferences(b, loadProperties(b/p(k)), k) } flatten
  }

  def loadProperties(path: File): Properties = {
    val p = new Properties
    (path * "*.properties" get) foreach { f =>
      Using.file(new FileInputStream(_))(f) { p.load _ }
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
        j <- findLibraryLibPath(b / d) * "*.jar" get
      } yield Attributed.blank(j.getCanonicalFile)) ++ (for {
        d <- Seq(b / "libs", b / "lib")
        j <- d * "*.jar" get
      } yield Attributed.blank(j.getCanonicalFile))
    ) filter { !_.data.getName.startsWith("scala-library") }
  }
}
