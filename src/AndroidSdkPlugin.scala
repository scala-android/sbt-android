import proguard.{Configuration => PgConfig, ProGuard, ConfigurationParser}
import scala.collection.JavaConversions._

import com.android.sdklib.{IAndroidTarget,SdkManager,SdkConstants}
import com.android.sdklib.build.ApkBuilder

import java.util.Properties
import java.io.{File,FilenameFilter,FileInputStream}

import scala.io.Source
import scala.xml.{XML,Elem}

import sbt._
import sbt.Keys._
import AndroidKeys._

object AndroidSdkPlugin extends Plugin {

    private var createDebug = true

    private def using[A <: { def close() },B](closeable: => A)(f: A => B):
            Option[B] = {
        var c: Option[A] = None
        try {
            c = Option(closeable)
            c map f
        } finally
            c foreach(_.close)
    }

    // android build steps
    // * handle library dependencies (android.library.reference.N)
    // * ndk TODO
    // * aidl TODO
    // * renderscript TODO
    // * aapt
    // * compile
    // * obfuscate
    // * dex
    // * png crunch
    // * package resources
    // * package apk
    // * sign
    // * zipalign

    lazy val androidBuildSettings: Seq[Setting[_]] =
    inConfig(Compile) (Seq(
        scalaSource       <<= baseDirectory (_ / "src"),
        javaSource        <<= baseDirectory (_ / "src"),
        unmanagedBase     <<= baseDirectory (_ / "libs"),
        resourceDirectory <<= baseDirectory (_ / "res"),
        unmanagedJars     <<= (unmanagedJars,
                baseDirectory, libraryProjects in Android, streams) map {
            (u, b, l, s) =>

            // remove scala-library if present
            // add all dependent library projects' classes.jar files
            (u ++ (l map { p =>
                    Attributed.blank(
                            (b / p / "bin" / "classes.jar").getCanonicalFile)
                }
            ) ++ (for (d <- l; j <- (b / d / "libs") * "*.jar" get)
                yield Attributed.blank(j.getCanonicalFile))
            ) filter { !_.data.getName.startsWith("scala-library") }
        },
        classDirectory    <<= (binPath in Android) (_ / "classes"),
        sourceGenerators  <+= aapt in Android,
        packageConfiguration in packageBin <<= (
                packageConfiguration in packageBin,
                libraryProject in Android,
                classesJar in Android) map { (c, l, j) =>
            new Package.Configuration(c.sources, j, c.options)
        },
        Keys.`package`    <<= Keys.`package` dependsOn(compile,
                pngCrunch in Android),
        javacOptions      <<= (platformJar in Android) (j =>
            Seq("-bootclasspath", j)
        ),
        scalacOptions     <<= (platformJar in Android) map {j =>
            Seq("-bootclasspath", j, "-javabootclasspath", j)
        }
    )) ++ inConfig(Android) (Seq(
        binPath             <<= baseDirectory (_ / "bin"),
        classesJar          <<= binPath (_ / "classes.jar"),
        classesDex          <<= binPath (_ / "classes.dex"),
        aapt                <<= aaptTaskDef,
        pngCrunch           <<= pngCrunchTaskDef,
        genPath             <<= baseDirectory (_ / "gen"),
        libraryProjects     <<= properties { p =>
            p.stringPropertyNames.collect {
                case k if k.startsWith("android.library.reference") => k
            }.toList.sortWith { (a,b) => a < b } map { k => p(k) }
        },
        libraryProject      <<= properties { p =>
            Option(p.getProperty("android.library")) map {
                    _.equals("true") } getOrElse false },
        aaptPath            <<= sdkPath {
            import SdkConstants._
            _ + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_AAPT
        },
        dexPath             <<= sdkPath {
            import SdkConstants._
            _ + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_DX
        },
        zipalignPath        <<= sdkPath {
            import SdkConstants._
            _ + OS_SDK_TOOLS_FOLDER + FN_ZIPALIGN
        },
        dex                 <<= dexTaskDef,
        platformJar         <<= platform (
                _.getPath(IAndroidTarget.ANDROID_JAR)),
        manifestPath        <<= baseDirectory (_ / "AndroidManifest.xml"),
        properties          <<= baseDirectory (b => loadProperties(b)),
        manifest            <<= manifestPath { m => XML.loadFile(m) },
        versionCode         <<= manifest { m =>
            val ns = m.getNamespace("android")
            m.attribute(ns, "versionCode") map { _(0) text }},
        versionName         <<= manifest { m =>
            val ns = m.getNamespace("android")
            m.attribute(ns, "versionName") map { _(0) text }},
        packageName         <<= manifest { m =>
            m.attribute("package") get (0) text
        },
        proguardOptions      := Seq.empty,
        proguardConfig       := proguardConfigDef,
        proguard            <<= proguardTaskDef,
        proguard            <<= proguard dependsOn (Keys.`package` in Compile),
        useProguard         <<= (scalaSource in Compile) {
            s => (s ** "*.scala").get.size > 0
        },
        packageResources    <<= packageResourcesTaskDef,
        packageResources    <<= packageResources dependsOn(pngCrunch),
        apkbuild            <<= apkbuildTaskDef,
        signRelease         <<= signReleaseTaskDef,
        zipalign            <<= zipalignTaskDef,
        Keys.`package`      <<= zipalign map identity,
        setDebug             := { createDebug = true },
        setRelease           := { createDebug = false },
        // I hope packageXXX dependsOn(setXXX) sets createDebug before package
        // because of how packageXXX is implemented by using task.?
        packageDebug        <<= Keys.`package`.task.? {
            _ map identity getOrElse sys.error("package failed")
        },
        packageDebug        <<= packageDebug dependsOn(setDebug),
        packageRelease      <<= Keys.`package`.task.? {
            _ map identity getOrElse sys.error("package failed")
        },
        packageRelease      <<= packageRelease dependsOn(setRelease),
        sdkPath             <<= properties (_("sdk.dir") + File.separator),
        sdkManager          <<= sdkPath (p =>
            SdkManager.createManager(p, null)),
        platform            <<= (sdkManager, properties) { (m, p) =>
            m.getTargetFromHashString(p("target"))
        }
    )) ++ Seq(
        cleanFiles    <+= binPath in Android,
        cleanFiles    <+= genPath in Android
    )

    private def packageResourcesTaskDef = ( aaptPath
                                          , manifestPath
                                          , versionCode
                                          , versionName
                                          , baseDirectory
                                          , binPath
                                          , libraryProjects
                                          , platformJar
                                          , streams
                                          ) map {
        (a, m, v, n, b, bin, l, j, s) =>
        val rel = if (createDebug) "-debug" else "-release"
        val basename = "resources" + rel + ".ap_"
        val dfile = bin * (basename + ".d") get
        val p = bin / basename
        val vc = v getOrElse sys.error("versionCode is not set")
        val vn = n getOrElse sys.error("versionName is not set")

        // crunched path needs to go before uncrunched
        val libraryResources = for (r <- l;
                arg <- Seq("-S", (b / r / "bin" / "res").getCanonicalPath,
                        "-S", (b / r / "res").getCanonicalPath))
                yield arg

        val libraryAssets = for (r <- l;
                arg <- Seq("-A", (b / r / "assets").getCanonicalPath)) yield arg

        if (dfile.size == 0 || outofdate(dfile(0))) {
            val debug = if (createDebug) Seq("--debug-mode") else Seq.empty
            val cmd = Seq(a, "package", "-f",
                // only required if refs lib projects, doesn't hurt otherwise?
                "--auto-add-overlay",
                "-M", m.absolutePath, // manifest
                "-S", (bin / "res").absolutePath, // crunched png path
                "-S", (b / "res").absolutePath, // resource path
                "-A", (b / "assets").absolutePath,
                "--generate-dependencies", // generate .d file
                "-I", j,
                "--no-crunch",
                "-F", p.getAbsolutePath
                ) ++ libraryResources ++ debug

            val r = cmd !

            if (r != 0) sys.error("failed")
        } else s.log.info(p.getName + " is up-to-date")
        p
    }

    // FIXME this fails when new files are added but none are modified
    private def outofdate(dfile: File): Boolean = {
        (using (Source.fromFile(dfile)) { s =>
            import collection.mutable.HashMap
            val dependencies = (s.getLines.foldLeft(
                    (new HashMap[String,Seq[String]], Option[String](null))) {
                case ((deps, dep), line) =>
                    val d = if (dep.isEmpty) {
                        Option(line.init.trim)
                    } else if (!line.endsWith("\\")) {
                        None
                    } else dep

                    dep foreach { n =>
                        val l = line.stripSuffix("\\").trim
                                .stripPrefix(":").trim
                        deps.update(n, (deps.get(n) getOrElse Seq.empty) :+ l)
                    }
                (deps, d)
            })._1

            dependencies.find { case (d,l) =>
                val f = new File(d)
                l.find { i =>
                    new File(i).lastModified > f.lastModified }.isDefined
            }.isDefined
        }) getOrElse false
    }

    private def apkbuildTaskDef = ( name
                                  , packageResources
                                  , dex
                                  , baseDirectory
                                  , binPath
                                  , libraryProjects
                                  , unmanagedBase in Compile
                                  , unmanagedJars in Compile
                                  , managedClasspath in Compile
                                  ) map {
       (n, r, d, base, b, p, l, u, m) =>
        val rel = if (createDebug) "-debug-unaligned.apk"
            else "-release-unsigned.apk"
        val pkg = n + rel
        val output = b / pkg

        val builder = new ApkBuilder(output, r, d,
                if (createDebug) ApkBuilder.getDebugKeystore else null,
                null)

        builder.setDebugMode(createDebug)
        (m ++ u) foreach { j => builder.addResourcesFromJar(j.data) }

        ((for (path <- p; lib <- ApkBuilder.getNativeFiles(
                base / path / "libs", createDebug))
            yield lib) ++ ApkBuilder.getNativeFiles(l, createDebug)) foreach {
                f => builder.addNativeLibraries(f.mFile) }

        builder.sealApk()

        output
    }

    private def signReleaseTaskDef = (properties, apkbuild, streams) map {
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
                sign(a, alias, options) {
                    (jarsigner, args) => (jarsigner +: args) !
                }

                s.log.info("Signed: " + signed.getName)
                signed
            case _ =>
                s.log.warn("Package needs signing: " + a.getName)
                a
            }
        }
    }

    private def zipalignTaskDef = (zipalignPath, signRelease, streams) map {
        (z, r, s) =>
        if (r.getName.contains("-unsigned")) {
            s.log.warn("Package needs signing and zipaligning: " + r.getName)
            r
        } else {
            val bin = r.getParentFile
            val aligned = bin / r.getName.replace("-unaligned", "")

            val rv = Seq(z, "-f", "4", r.getAbsolutePath, aligned.getAbsolutePath) !

            if (rv != 0) sys.error("failed")

            s.log.info("zipaligned: " + aligned.getName)
            aligned
        }
    }
    private def aaptTaskDef = ( manifestPath
                              , baseDirectory
                              , packageName
                              , platformJar
                              , genPath
                              , aaptPath
                              , libraryProjects
                              , streams
                              ) map {
        (m, b, p, j, g, a, l, s) =>
        g.mkdirs()
        val libraryResources = for (r <- l;
                arg <- Seq("-S", (b / r / "res").getCanonicalPath)) yield arg

        val dfile = (g * "R.java.d" get)
        if (dfile.size == 0 || outofdate(dfile(0))) {
            val r = Seq(a, "package",
                // only required if refs lib projects, doesn't hurt otherwise?
                "--auto-add-overlay",
                "-m", // make package directories in gen
                //"--generate-dependencies", // generate R.java.d
                "--custom-package", p, // package name
                "-M", m.absolutePath, // manifest
                "-S", (b / "res").absolutePath, // resource path
                "-I", j, // platform jar
                "-J", g.absolutePath) ++ libraryResources !

            if (r != 0) sys.error("failed")
        } else s.log.info("R.java is up-to-date")
        g ** "R.java" get
    }

    private def pngCrunchTaskDef = (aaptPath, binPath, baseDirectory) map {
        (a, bin, base) =>
        Seq(a, "crunch", "-v",
            "-S", (base / "res").absolutePath,
            "-C", (bin / "res").absolutePath) !

        ()
    }

    private def proguardConfigDef = {
        using(AndroidSdkPlugin.getClass
                .getClassLoader.getResourceAsStream(
                        "android-proguard.config")) { in =>
            Seq(Source.fromInputStream(in).getLines.toSeq: _*)
        } getOrElse Seq[String]()
    }

    private def dexTaskDef = (dexPath
                             , proguard
                             , classesDex
                             , managedClasspath in Compile
                             , unmanagedJars in Compile
                             , streams) map {
        (d, p, c, m, u, s) =>
        // TODO remove duplicate jars
        val inputs = p map { f => Seq(f) } getOrElse {
            (m ++ u) collect {
                // no proguard? then we don't need to dex scala!
                case x if !x.data.getName.startsWith("scala-library") =>
                    x.data
            }
        }
        if (inputs.find { _.lastModified > c.lastModified }.isDefined) {
            s.log.info("dexing input")
            val r = Seq(d, "--dex",
                // TODO
                // --no-locals if instrumented
                // --verbose
                "--num-threads",
                "" + java.lang.Runtime.getRuntime.availableProcessors,
                "--output", c.getAbsolutePath
                ) ++ (inputs map { _.getAbsolutePath }) !

            if (r != 0) sys.error("failed")
        } else {
            s.log.info(c.getName + " is up-to-date")
        }

        c
    }
    // TODO save mappings file
    private def proguardTaskDef: Project.Initialize[Task[Option[File]]] =
            ( useProguard
            , proguardConfig
            , proguardOptions
            , libraryProject
            , binPath in Android
            , managedClasspath in Compile
            , unmanagedJars in Compile
            , platformJar
            , streams // 9 args max for task
            ) map {
        (p, c, o, l, b, m, u, j, s) =>
        if (p && !l) {
            // TODO remove duplicate jars
            val t = b / "classes.proguard.jar"
            val jars = ((m ++ u) map { _.data }) :+ (b/"classes.jar")
            if (jars.find { _.lastModified > t.lastModified }.isDefined) {
                val injars = "-injars " + (jars map {
                        _.getPath + "(!META-INF/**)" } mkString(
                                File.pathSeparator))
                val libraryjars = "-libraryjars " + j
                val outjars = "-outjars " + t.getAbsolutePath
                val cfg = c ++ o :+ libraryjars :+ injars :+ outjars
                val config = new PgConfig
                new ConfigurationParser(cfg.toArray[String]).parse(config)
                new ProGuard(config).execute
            } else {
                s.log.info(t.getName + " is up-to-date")
            }

            Option(t)
        } else None
    }

    private def loadProperties(path: File): Properties = {
        val p = new Properties
        (path * "*.properties" get) foreach { f =>
            using(new FileInputStream(f)) { in => p.load(in) }
        }
        p
    }
}

object AndroidKeys {
    val setDebug = TaskKey[Unit]("set-debug", "set debug build")
    val signRelease = TaskKey[File]("sign-release", "sign the release build")
    val zipalignPath = SettingKey[String]("zipalign-path",
            "path to the zipalign executable")
    val zipalign = TaskKey[File]("zipalign", "zipalign the final package")
    val setRelease = TaskKey[Unit]("set-release", "set release build")
    val pngCrunch = TaskKey[Unit]("png-crunch", "optimize png files")
    val packageName = SettingKey[String]("package-name",
            "android package name")
    val apkbuild = TaskKey[File]("apkbuild", "generates an apk")
    val packageRelease = TaskKey[File]("package-release",
            "create a release apk")
    val packageDebug = TaskKey[File]("package-debug",
            "create a debug apk")
    val packageResources = TaskKey[File]("package-resources",
            "package android resources")
    val manifestPath = SettingKey[File]("manifest-path",
            "android manifest file path")
    val manifest = SettingKey[Elem]("manifest",
            "android manifest xml object")
    val classesJar = SettingKey[File]("classes-jar",
            "generated classes.jar file if in a library project")
    val libraryProjects = SettingKey[Seq[String]]("library-projects",
            "android library projects to reference, must be built separately")
    val libraryProject = SettingKey[Boolean]("library-project",
            "flag indicating whether or not a library project")
    val binPath = SettingKey[File]("bin-path",
            "android compiled bin path")
    val genPath = SettingKey[File]("gen-path",
            "android generated code path")
    val properties = SettingKey[Properties]("properties",
            "Properties loaded from the project's .property files")
    val sdkPath = SettingKey[String]("sdk-path",
            "Path to the Android SDK")
    val sdkManager = SettingKey[SdkManager]("sdk-manager",
            "Android SdkManager object")
    val platform = SettingKey[IAndroidTarget]("platform",
            "IAndroidTarget object representing a target API level")
    val platformJar = SettingKey[String]("platform-jar", "Path to android.jar")
    val aaptPath = SettingKey[String]("aapt-path", "path to aapt")
    val aapt = TaskKey[Seq[File]]("aapt", "android aapt task")
    val dexPath = SettingKey[String]("dex-path", "path to dex")
    val dex = TaskKey[File]("dex", "run bytecode dexer")
    val classesDex = SettingKey[File]("classes-dex", "output classes.dex path")
    val versionName = SettingKey[Option[String]]("version-name",
            "application version name")
    val versionCode = SettingKey[Option[String]]("version-code",
            "application version code")
    val proguardOptions = SettingKey[Seq[String]]("proguard-options",
            "additional options to add to proguard-config")
    val proguardConfig = SettingKey[Seq[String]]("proguard-config",
            "base proguard configuration")
    val proguard = TaskKey[Option[File]]("proguard",
            "proguard task, generates obfuscated.jar")
    val useProguard = SettingKey[Boolean]("use-proguard",
            "whether or not to run proguard, automatically true with scala")

    val Android = config("android")
}
