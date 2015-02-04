package android

import com.android.ide.common.signing.KeystoreHelper
import com.android.manifmerger.ManifestMerger2
import sbt._
import sbt.Keys._
import classpath.ClasspathUtilities

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._
import scala.util.control.Exception._
import scala.xml._

import java.util.Properties
import java.io.{PrintWriter, File, FileInputStream}

import com.android.SdkConstants
import com.android.builder.model.{PackagingOptions, AaptOptions}
import com.android.builder.signing.DefaultSigningConfig
import com.android.builder.core._
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.DdmPreferences
import com.android.ddmlib.testrunner.ITestRunListener
import com.android.ide.common.res2.FileStatus
import com.android.ide.common.res2.FileValidity
import com.android.ide.common.res2.MergedResourceWriter
import com.android.ide.common.res2.ResourceMerger
import com.android.ide.common.res2.ResourceSet
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.BuildToolInfo.PathId
import com.android.utils.ILogger

import proguard.{Configuration => PgConfig, ProGuard, ConfigurationParser}

import Keys._
import Dependencies.{LibraryProject => _, AutoLibraryProject => _, _}
import com.android.builder.compiling.BuildConfigGenerator
import java.net.URLEncoder

object Tasks {
  val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  val INSTRUMENTATION_TAG = "instrumentation"
  val USES_LIBRARY_TAG = "uses-library"
  val APPLICATION_TAG = "application"
  val ANDROID_PREFIX = "android"
  val TEST_RUNNER_LIB = "android.test.runner"

  // TODO come up with a better solution
  // wish this could be protected
  private[android] var _createDebug = true

  val reservedWords = Set(
    "def",
    "forSome",
    "implicit",
    "lazy",
    "match",
    "object",
    "override",
    "sealed",
    "trait",
    "type",
    "val",
    "var",
    "with",
    "yield"
  )

  def createDebug = _createDebug

  def resourceUrl =
    Plugin.getClass.getClassLoader.getResource _

  val buildConfigGeneratorTaskDef = ( platformTarget
                                    , genPath
                                    , libraryProjects
                                    , packageForR
                                    ) map {
    (t, g, l, p) =>
    val b = new BuildConfigGenerator(g, p)
    b.addField("boolean", "DEBUG", createDebug.toString)
    b.generate()
    l collect {
      case a: ApkLibrary         => a
      case a: AutoLibraryProject => a
    } foreach { lib =>
      val b = new BuildConfigGenerator(g, lib.pkg)
      b.addField("boolean", "DEBUG", createDebug.toString)
      b.generate()
    }
    g ** "BuildConfig.java" get
  }

  def moduleForFile(u: UpdateReport, f: File): ModuleID = {
    val map: Map[File,ModuleID] =
      u.configurations.flatMap(_.modules).distinct.flatMap { m =>
        m.artifacts map { case (_,file) => (file,m.module) }
      } toMap

    map(f)
  }
  // takeWhile hack to bypass cross versions, I hope no real artifacts have
  // underscores in the name as a delimiter
  def moduleString(m: ModuleID) =
    m.organization + ":" + m.name.takeWhile(_ != '_')

  val aarsTaskDef = ( update in Compile
                    , localAars
                    , libraryDependencies in Compile
                    , transitiveAndroidLibs
                    , target
                    , streams
                    ) map {
    (u,local,d,tx,t,s) =>
    val libs = u.matching(artifactFilter(`type` = "aar"))
    val dest = t / "aars"

    val deps = d.filterNot(_.configurations.exists(
      _ contains "test")).map(moduleString).toSet
    (libs flatMap { l =>
      val m = moduleForFile(u, l)
      if (tx || deps(moduleString(m))) {
        val d = dest / (m.organization + "-" + m.name + "-" + m.revision)
        Some(unpackAar(l, d, s.log): LibraryDependency)
      } else {
        s.log.warn(m + " is not an explicit dependency, skipping")
        None
      }
    }) ++ (local map { a =>
      unpackAar(a, dest / ("localAAR-" + a.getName), s.log)
    })
  }

  def unpackAar(aar: File, dest: File, log: Logger): LibraryDependency = {
    val lib = AarLibrary(dest)
    if (dest.lastModified < aar.lastModified || !lib.getManifest.exists) {
      dest.delete()
      log.info("Unpacking aar: %s to %s" format (aar.getName, dest.getName))
      dest.mkdirs()
      IO.unzip(aar, dest)
    }
    // rename for sbt-idea when using multiple aar packages
    val renamedJar = lib.getJarFile.getParentFile / (dest.getName + ".jar")
    if (lib.getJarFile.exists) {
      lib.getJarFile.renameTo(renamedJar)
    }
    new AarLibrary(dest) {
      override def getJarFile = renamedJar
    }
  }

  val autolibsTaskDef = ( localProjects
                        , genPath
                        , libraryProject
                        , builder
                        , ilogger
                        , streams ) map {
   (prjs,gen,isLib,bldr,logger,s) =>
     prjs collect { case a: AutoLibraryProject => a } flatMap { lib =>
      s.log.info("Processing library project: " + lib.pkg)

      lib.getProguardRules.getParentFile.mkdirs
      // TODO collect resources from apklibs and aars
//      doCollectResources(bldr, true, true, Seq.empty, lib.layout,
//        logger, file("/"), s)
      aapt(bldr, lib.getManifest, null, Seq.empty, true,
          lib.getResFolder, lib.getAssetsFolder, null,
          lib.layout.gen, lib.getProguardRules.getAbsolutePath,
          s.log)

      def copyDirectory(src: File, dst: File) {
        IO.copy(((src ***) --- (src ** "R.txt")) pair Path.rebase(src, dst),
          false, true)
      }
      if (isLib)
        copyDirectory(lib.layout.gen, gen)
      Some(lib: LibraryDependency)
    }
  }

  val apklibsTaskDef = ( update in Compile
                       , libraryDependencies in Compile
                       , genPath
                       , libraryProject
                       , transitiveAndroidLibs
                       , target
                       , streams
                       , builder
                       , streams
                       ) map {
    (u,d,gen,isLib,tx,t,s,bldr,st) =>
    val libs = u.matching(artifactFilter(`type` = "apklib"))
    val dest = t / "apklibs"
    val deps = d.filterNot(_.configurations.exists(
      _ contains "test")).map(moduleString).toSet
    libs flatMap { l =>
      val m = moduleForFile(u, l)
      if (tx || deps(moduleString(m))) {
        val d = dest / (m.organization + "-" + m.name + "-" + m.revision)
        val lib = ApkLibrary(d)
        if (d.lastModified < l.lastModified || !lib.getManifest.exists) {
          s.log.info("Unpacking apklib: " + m)
          d.mkdirs()
          IO.unzip(l, d)

          aapt(bldr, lib.getManifest, null, Seq.empty, true,
              lib.getResFolder, lib.getAssetsFolder, null,
              lib.layout.gen, lib.getProguardRules.getAbsolutePath,
              st.log)

        }
        def copyDirectory(src: File, dst: File) {
          IO.copy(((src ***) --- (src ** "R.txt")) pair Path.rebase(src, dst),
            false, true)
        }
        if (isLib)
          copyDirectory(lib.layout.gen, gen)
        Some(lib: LibraryDependency)
      } else {
        s.log.warn(m + " is not an explicit dependency, skipping")
        None
      }
    }
  }

  val typedResourcesGeneratorTaskDef = ( typedResources
                                       , rGenerator
                                       , packageForR
                                       , projectLayout
                                       , platformJars
                                       , scalaVersion in ThisProject
                                       , libraryProjects
                                       , typedResourcesIgnores
                                       , streams
                                       ) map {
    case (t, a, p, layout, (j, x), sv, l, i, s) =>

    val r = layout.res
    val g = layout.gen
    val ignores = i.toSet

    val tr = p.split("\\.").foldLeft (g) { _ / _ } / "TR.scala"

    if (!t)
      Seq.empty[File]
    else
      FileFunction.cached(s.cacheDirectory / "typed-resources-generator",
          FilesInfo.hash, FilesInfo.hash) { in =>
        if (in.nonEmpty) {
          s.log.info("Regenerating TR.scala because R.java has changed")
          val androidjar = ClasspathUtilities.toLoader(file(j))
          val layouts = (r ** "layout*" ** "*.xml" get) ++
            (for {
              lib <- l filterNot {
                case p: Pkg => ignores(p.pkg)
                case _      => false
              }
              xml <- lib.getResFolder ** "layout*" ** "*.xml" get
            } yield xml)

          s.log.debug("Layouts: " + layouts)
          // XXX handle package references? @id/android:ID or @id:android/ID
          val re = "@\\+id/(.*)".r

          def classForLabel(l: String) = {
            if (l contains ".") Some(l)
            else {
              Seq("android.widget."
                , "android.view."
                , "android.webkit.").flatMap {
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

          val gte210 = ((sv split "\\.")(1) toInt) >= 10
          val implicitsImport = if (!gte210) "" else
            "import scala.language.implicitConversions"

          tr.delete()
          def wrap(s: String) = if (reservedWords(s)) "`%s`" format s else s
          IO.write(tr, trTemplate format (p, implicitsImport,
            resources map { case (k,v) =>
              "  val %s = TypedResource[%s](R.id.%s)" format (wrap(k),v,wrap(k))
            } mkString "\n",
            layoutTypes map { case (k,v) =>
              "    val %s = TypedLayout[%s](R.layout.%s)" format (wrap(k),v,wrap(k))
            } mkString "\n"))
          Set(tr)
        } else Set.empty
      }(a.toSet).toSeq
  }

  def ndkbuild(layout: ProjectLayout,
               ndkHome: Option[String], srcs: File, log: Logger) = {
    val hasJni = (layout.jni ** "Android.mk" get).size > 0
    if (hasJni) {
      if (ndkHome.isEmpty) {
        log.warn(
          "Android.mk found, but neither ndk.dir nor ANDROID_NDK_HOME are set")
      }
      ndkHome flatMap { ndk =>
        val env = Seq("NDK_PROJECT_PATH" -> (layout.jni / "..").getAbsolutePath,
          "NDK_OUT" -> (layout.bin / "obj").getAbsolutePath,
          "NDK_LIBS_OUT" -> (layout.bin / "jni").getAbsolutePath,
          "SBT_SOURCE_MANAGED" -> srcs.getAbsolutePath)

        log.info("Executing NDK build")
        val ndkbuildFile = if (!Commands.isWindows) file(ndk) / "ndk-build" else  {
          val f = file(ndk) / "ndk-build.cmd"
          if (f.exists) f else file(ndk) / "ndk-build.bat"
        }

        val rc = Process(ndkbuildFile.getAbsolutePath, layout.base, env: _*) !

        if (rc != 0)
          sys.error("ndk-build failed!")

        Option(layout.bin / "jni")
      }
    } else None
  }

  val ndkJavahTaskDef = ( sourceManaged in Compile
                        , compile in Compile
                        , classDirectory in Compile
                        , fullClasspath in Compile
                        , builder
                        , streams
                        ) map { (src, c, classes, cp, bldr, s) =>
    val natives = NativeFinder.natives(classes)

    if (natives.size > 0) {
      val javah = Seq("javah",
        "-d", src.getAbsolutePath,
        "-classpath", cp map (_.data.getAbsolutePath) mkString File.pathSeparator,
        "-bootclasspath", bldr.getBootClasspath mkString File.pathSeparator) ++ natives

      s.log.debug(javah mkString " ")

      val rc = javah !

      if (rc != 0)
        sys.error("Failed to execute: " + (javah mkString " "))

      src ** "*.h" get
    } else Seq.empty
  }
  val ndkBuildTaskDef = ( projectLayout
                        , libraryProjects
                        , sourceManaged in Compile
                        , ndkJavah
                        , properties
                        , streams
                        ) map { (layout, libs, srcs, h, p, s) =>
    val ndkHome = Option(System.getenv("ANDROID_NDK_HOME")) orElse Option(
      p getProperty "ndk.dir")

    val subndk = libs map { l => ndkbuild(l.layout, ndkHome, srcs, s.log) }

    Seq(ndkbuild(layout, ndkHome, srcs, s.log)).flatten ++ subndk.flatten
  }

  val collectJniTaskDef = ( libraryProjects
                          , ndkBuild
                          , projectLayout
                          , streams
                          ) map {
    (libs, ndk, layout, s) =>
    // TODO traverse entire library dependency graph, goes 2 deep currently
    (Seq(LibraryProject(layout.base)) ++ libs ++ libs.flatMap { l =>
      l.getDependencies map { _.asInstanceOf[LibraryDependency] }
    } collect {
      case r if r.getJniFolder.isDirectory => r.getJniFolder
    }) ++ ndk
  }
  def doCollectResources( bldr: AndroidBuilder
                        , noTestApk: Boolean
                        , isLib: Boolean
                        , libs: Seq[LibraryDependency]
                        , layout: ProjectLayout
                        , logger: Logger => ILogger
                        , cache: File
                        , s: TaskStreams
                        ): (File,File) = {

    val assetBin = layout.bin / "assets"
    val assets = layout.assets
    val resTarget = layout.bin / "resources" / "res"
    val rsResources = layout.bin / "renderscript" / "res"

    resTarget.mkdirs()
    assetBin.mkdirs

    // copy assets to single location
    libs collect {
      case r if r.layout.assets.isDirectory => r.layout.assets
    } foreach { a => IO.copyDirectory(a, assetBin, false, true) }

    if (assets.exists) IO.copyDirectory(assets, assetBin, false, true)
    if (noTestApk && layout.testAssets.exists)
      IO.copyDirectory(layout.testAssets, assetBin, false, true)
    // prepare resource sets for merge
    val res = Seq(layout.res, rsResources) ++
      (libs map { _.layout.res } filter { _.isDirectory })

    s.log.debug("Local/library-project resources: " + res)
    // this needs to wait for other projects to at least finish their
    // apklibs tasks--handled if androidBuild() is called properly
    val depres = collectdeps(libs) collect {
      case m: ApkLibrary => m
      case n: AarLibrary => n
    } collect { case n if n.getResFolder.isDirectory => n.getResFolder }
    s.log.debug("apklib/aar resources: " + depres)

    val respaths = depres ++ res.reverse ++
      (if (layout.res.isDirectory) Seq(layout.res) else Seq.empty) ++
      (if (noTestApk && layout.testRes.isDirectory)
        Seq(layout.res) else Seq.empty)
    val sets = respaths.distinct map { r =>
      val set = new ResourceSet(r.getAbsolutePath)
      set.addSource(r)
      s.log.debug("Adding resource path: " + r)
      set
    }

    val inputs = (respaths map { r => (r ***) get } flatten) filter (n =>
      !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    FileFunction.cached(cache / "collect-resources")(
      FilesInfo.lastModified, FilesInfo.exists) { (inChanges,outChanges) =>
      s.log.info("Collecting resources")
      incrResourceMerge(layout.base, resTarget, isLib, libs,
        cache / "collect-resources", logger(s.log), bldr, sets, inChanges, s.log)
      (resTarget ***).get.toSet
    }(inputs toSet)

    (assetBin, resTarget)
  }

  val collectResourcesTaskDef = ( builder
                                , debugIncludesTests
                                , libraryProject
                                , libraryProjects
                                , projectLayout
                                , ilogger
                                , streams
                                ) map {
    (bldr, noTestApk, isLib, libs, layout, logger, s) =>
      doCollectResources(bldr, noTestApk, isLib, libs, layout, logger, s.cacheDirectory, s)
  }

  def incrResourceMerge(base: File, resTarget: File, isLib: Boolean,
      libs: Seq[LibraryDependency], blobDir: File, logger: ILogger,
      bldr: AndroidBuilder, resources: Seq[ResourceSet],
      changes: ChangeReport[File], slog: Logger) {

    def merge() = fullResourceMerge(base, resTarget, isLib, libs, blobDir,
      logger, bldr, resources, slog)
    val merger = new ResourceMerger
    if (!merger.loadFromBlob(blobDir, true)) {
      slog.debug("Could not load merge blob (no full merge yet?)")
      merge()
    } else if (!merger.checkValidUpdate(resources)) {
      slog.debug("requesting full merge: !checkValidUpdate")
      merge()
    } else {

      val fileValidity = new FileValidity[ResourceSet]
      val exists = changes.added ++ changes.removed ++ changes.modified exists {
        file =>
        val status = if (changes.added contains file)
          FileStatus.NEW
        else if (changes.removed contains file)
          FileStatus.REMOVED
        else if (changes.modified contains file)
          FileStatus.CHANGED
        else
          sys.error("Unknown file status: " + file)

        merger.findDataSetContaining(file, fileValidity)
        val vstatus = fileValidity.getStatus

        if (vstatus == FileValidity.FileStatus.UNKNOWN_FILE) {
          merge()
          slog.debug("Incremental merge aborted, unknown file: " + file)
          true
        } else if (vstatus == FileValidity.FileStatus.VALID_FILE) {
          // begin workaround
          // resource merger doesn't seem to actually copy changed files over...
          // values.xml gets merged, but if files are changed...
          val targetFile = resTarget / (
            file relativeTo fileValidity.getSourceFile).get.getPath
          val copy = Seq((file, targetFile))
          status match {
            case FileStatus.NEW =>
            case FileStatus.CHANGED =>
              if (targetFile.exists) IO.copy(copy, false, true)
            case FileStatus.REMOVED => targetFile.delete()
          }
          // end workaround
          try {
            if (!fileValidity.getDataSet.updateWith(
                fileValidity.getSourceFile, file, status, logger)) {
              slog.debug("Unable to handle changed file: " + file)
              merge()
              true
            } else
              false
          } catch {
            case e: RuntimeException =>
              slog.warn("Unable to handle changed file: " + file + ": " + e)
              merge()
              true
          }
        } else
          false
      }
      if (!exists) {
        slog.info("Performing incremental resource merge")
        val writer = new MergedResourceWriter(resTarget, bldr.getAaptCruncher, true, true)
        merger.mergeData(writer, true)
        merger.writeBlobTo(blobDir, writer)
      }
    }
  }
  def fullResourceMerge(base: File, resTarget: File, isLib: Boolean,
    libs: Seq[LibraryDependency], blobDir: File, logger: ILogger,
    bldr: AndroidBuilder, resources: Seq[ResourceSet], slog: Logger) {

    slog.info("Performing full resource merge")
    val merger = new ResourceMerger

    resTarget.mkdirs()

    resources foreach { r =>
      r.loadFromFiles(logger)
      merger.addDataSet(r)
    }
    val writer = new MergedResourceWriter(resTarget, bldr.getAaptCruncher, true, true)
    merger.mergeData(writer, false)
    merger.writeBlobTo(blobDir, writer)
  }

  val packageApklibTaskDef = ( manifestPath
                             , projectLayout
                             , name
                             , streams
                             ) map { (m, layout, n, st) =>
    val outfile = layout.bin / (n + ".apklib")
    st.log.info("Packaging " + outfile.getName)
    import layout._
    val mapping =
      (PathFinder(manifest)                 pair flat) ++
      (PathFinder(javaSource) ** "*.java"   pair rebase(javaSource,  "src")) ++
      (PathFinder(scalaSource) ** "*.scala" pair rebase(scalaSource, "src")) ++
      ((PathFinder(res) ***)                pair rebase(res,         "res")) ++
      ((PathFinder(assets) ***)             pair rebase(assets,      "assets"))
    IO.jar(mapping, outfile, new java.util.jar.Manifest)
    outfile
  }
  val packageAarTaskDef = ( packageT in Compile
                          , projectLayout
                          , collectResources
                          , name
                          , streams
                          ) map { case (j, layout, (_, r), n, s) =>
    import layout._
    val outfile = bin / (n + ".aar")
    val rsRes = bin / "renderscript" / "res"
    s.log.info("Packaging " + outfile.getName)
    val mapping =
      (PathFinder(manifest)             pair flat) ++
      (PathFinder(gen / "R.txt")        pair flat) ++
      (PathFinder(bin / "proguard.txt") pair flat) ++
      (PathFinder(j)                    pair flat) ++
      ((PathFinder(res) ***)            pair rebase(res,    "res")) ++
      ((PathFinder(rsRes) ***)          pair rebase(rsRes,  "res")) ++
      ((PathFinder(assets) ***)         pair rebase(assets, "assets"))
    IO.jar(mapping, outfile, new java.util.jar.Manifest)
    outfile
  }

  val packageResourcesTaskDef = ( builder
                                , projectLayout
                                , processManifest
                                , collectResources
                                , packageForR
                                , libraryProject
                                , libraryProjects
                                , streams
                                ) map {
    case (bldr, layout, manif, (assets, res), pkg, lib, libs, s) =>
    val cache = s.cacheDirectory
    val proguardTxt = (layout.bin / "proguard.txt").getAbsolutePath

    val rel = if (createDebug) "-debug" else "-release"
    val basename = "resources" + rel + ".ap_"
    val p = layout.bin / basename

    val inputs = (res ***).get ++ (assets ***).get ++
      Seq(manif)filter (
        n => !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    FileFunction.cached(cache / basename, FilesInfo.hash) { _ =>
      s.log.info("Packaging resources: " + p.getName)
      aapt(bldr, manif, pkg, libs, lib, res, assets,
        p.getAbsolutePath, layout.gen, proguardTxt, s.log)
      Set(p)
    }(inputs.toSet)
    p
  }

  val apkbuildTaskDef = ( builder
                        , state
                        , thisProjectRef
                        , packageResources
                        , dex
                        , unmanagedJars in Compile
                        , managedClasspath
                        , dependencyClasspath in Compile
                        , collectJni
                        , streams
                        ) map {
    (bldr, st, prj, r, d, u, m, dcp, jni, s) =>
    val extracted = Project.extract(st)
    import extracted._

    val n = get(name in prj)
    val excl = get(apkbuildExcludes in (prj, Android))
    val fsts = get(apkbuildPickFirsts in (prj, Android))
    val layout = get(projectLayout in (prj, Android))
    val logger = get(ilogger in (prj, Android))
    val cacheDir = s.cacheDirectory

    val jars = (m ++ u ++ dcp).filter {
      a => (a.get(moduleID.key) map { mid =>
        mid.organization != "org.scala-lang" &&
          !(mid.configurations exists (_ contains "provided"))
      } getOrElse true) && a.data.exists
    }.groupBy(_.data.getName).collect {
      case ("classes.jar",xs) => xs.distinct
      case (_,xs) if xs.head.data.isFile => xs.head :: Nil
    }.flatten.map (_.data).toList

    // workaround for https://code.google.com/p/android/issues/detail?id=73437
    val collectedJni = layout.bin / "collect-jni"
    if (jni.size > 0) {
      collectedJni.mkdirs()
      val copyList = for {
        j <- jni
        l <- j ** "*.so" get
      } yield (l, collectedJni / (l relativeTo j).get.getPath)

      IO.copy(copyList)
    } else {
      IO.delete(collectedJni)
    }
    // end workaround

    s.log.debug("jars to process for resources: " + jars)

    val debugConfig = new DefaultSigningConfig("debug")
    debugConfig.initDebug()
    if (!debugConfig.getStoreFile.exists) {
      KeystoreHelper.createDebugStore(null, debugConfig.getStoreFile,
        debugConfig.getStorePassword, debugConfig.getKeyPassword,
        debugConfig.getKeyAlias, logger(s.log))
    }

    val rel = if (createDebug) "-debug-unaligned.apk"
      else "-release-unsigned.apk"
    val pkg = n + rel
    val output = layout.bin / pkg

    // filtering out org.scala-lang above should not cause an issue
    // they should not be changing on us anyway
    val deps = Set(r +: ((d * "*.dex" get) ++ jars ++ jni):_*)


    FileFunction.cached(cacheDir / pkg, FilesInfo.hash) { in =>
      val options = new PackagingOptions {
        override def getExcludes = excl.toSet.asJava
        override def getPickFirsts = fsts.toSet.asJava
        override def toString =
          "PackagingOptions[%s, %s]" format (getExcludes, getPickFirsts)
      }
      s.log.debug("bldr.packageApk(%s, %s, %s, null, %s, %s, %s, %s, %s)" format (
        r.getAbsolutePath, d.getAbsolutePath, jars,
          if (collectedJni.exists) Seq(collectedJni) else Seq.empty, createDebug,
          if (createDebug) debugConfig else null, output.getAbsolutePath,
          options
      ))
      bldr.packageApk(r.getAbsolutePath, d, Seq.empty[File], jars,
        layout.resources.getAbsolutePath,
        (if (collectedJni.exists) Seq(collectedJni) else Seq.empty).asJava,
        null, createDebug,
        if (createDebug) debugConfig else null, options, output.getAbsolutePath)
      s.log.info("Packaged: %s (%s)" format (
        output.getName, sizeString(output.length)))
      Set(output)
    }(deps)

    output
  }

  val signReleaseTaskDef = (apkSigningConfig, apkbuild, streams) map {
    (c, a, s) =>
    val bin = a.getParentFile
    if (createDebug) {
      s.log.info("Debug package does not need signing: " + a.getName)
      a
    } else {
      c map { cfg =>
        import SignJar._
        val signed = bin / a.getName.replace("-unsigned", "-unaligned")
        val options = Seq( storeType(cfg.storeType)
          , storePassword(cfg.storePass)
          , signedJar(signed)
          , keyStore(cfg.keystore.toURI.toURL)
        )

        val kp = cfg.keyPass map { p => keyPassword(p) }
        sign(a, cfg.alias, options ++ kp) { (jarsigner, args) =>
          (jarsigner +: (args ++ Seq(
            "-digestalg", "SHA1", "-sigalg", "MD5withRSA"))) !
        }

        s.log.info("Signed: " + signed.getName)
        signed
      } getOrElse {
        s.log.warn("Package needs signing: " + a.getName)
        a
      }
    }
  }

  val zipalignTaskDef = (zipalignPath, signRelease, apkFile, streams) map {
    (z, r, a, s) =>
    if (r.getName.contains("-unsigned")) {
      s.log.warn("Package needs signing and zipaligning: " + r.getName)
      a.delete()
      r
    } else {
      val bin = r.getParentFile
      val aligned = bin / r.getName.replace("-unaligned", "")

      val rv = Seq(z, "-f", "4", r.getAbsolutePath, aligned.getAbsolutePath) !

      if (rv != 0) {
        sys.error("failed")
      }

      s.log.info("zipaligned: " + aligned.getName)
      IO.copyFile(aligned, a)
      aligned
    }
  }

  val renderscriptTaskDef = ( sdkPath
                            , sdkManager
                            , projectLayout
                            , platform
                            , streams
                            ) map { (s, m, layout, t, l) =>
    import SdkConstants._

    val tools = Option(m.getLatestBuildTool)
    val (rs, rsInclude, rsClang) = tools map { t =>
      (t.getPath(PathId.LLVM_RS_CC)
      ,t.getPath(PathId.ANDROID_RS)
      ,t.getPath(PathId.ANDROID_RS_CLANG))
    } getOrElse {
      val prefix = s + OS_SDK_PLATFORM_TOOLS_FOLDER
      (prefix + FN_RENDERSCRIPT
      ,prefix + OS_FRAMEWORK_RS
      ,prefix + OS_FRAMEWORK_RS_CLANG
      )
    }

    val scripts = for {
      script <- layout.renderscript ** "*.rs" get
    } yield (layout.renderscript,script)

    val v = t.getVersion.getApiString
    val targetNumber = catching(classOf[NumberFormatException]) opt { v.toInt }
    val target = targetNumber map { n => if (n < 11) "11" else v } getOrElse v

    scripts flatMap { case (src, script) =>

      // optimization level -O also requires sdk r17
      // debug requires sdk r17
      //val debug = if (createDebug) Seq("-g") else Seq.empty

      val cmd = Seq(rs, "-I", rsInclude, "-I", rsClang,
        "-target-api", target,
        "-d", (layout.gen /
          (script relativeTo src).get.getParentFile.getPath).getAbsolutePath,
        //"-O", level, 0 through 3
        "-MD", "-p", layout.gen.getAbsolutePath,
        "-o", (layout.bin / "renderscript" / "res" / "raw").getAbsolutePath) :+
          script.getAbsolutePath

      val r = cmd !

      if (r != 0)
        sys.error("renderscript failed: " + r)

      (layout.gen ** (script.getName.stripSuffix(".rs") + ".d") get) flatMap {
        dfile =>
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
                    , sdkManager
                    , projectLayout
                    , platform
                    , streams
                    ) map { (s, m, layout, p, l) =>
    import SdkConstants._
    val tools = Option(m.getLatestBuildTool)
    val aidl          = tools map (_.getPath(PathId.AIDL)) getOrElse {
        s + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_AIDL
    }
    val frameworkAidl = p.getPath(IAndroidTarget.ANDROID_AIDL)
    val aidls = layout.aidl ** "*.aidl" get

    aidls flatMap { idl =>
      val out = layout.gen ** (idl.getName.stripSuffix(".aidl") + ".java") get
      val cmd = Seq(aidl,
        "-p" + frameworkAidl,
        "-o" + layout.gen.getAbsolutePath,
        "-a",
        "-I" + layout.aidl.getAbsolutePath
        ) :+ idl.getAbsolutePath

      // TODO FIXME this doesn't account for other dependencies
      if (out.isEmpty || (out exists { idl.lastModified > _.lastModified })) {
        val r = cmd !

        if (r != 0)
          sys.error("aidl failed")

        layout.gen ** (idl.getName.stripSuffix(".aidl") + ".java") get
      } else out
    }
  }

  val processManifestTaskDef = ( builder
                               , debugIncludesTests
                               , projectLayout
                               , libraryProject
                               , libraryProjects
                               , packageName
                               , state
                               , thisProjectRef
                               , instrumentTestRunner
                               , manifestPlaceholders
                               ) map {
    (bldr, noTestApk, layout, isLib, libs, pkg, st, prj, trunner, ph) =>
    val extracted = Project.extract(st)
    val vc = extracted.get(versionCode in (prj,Android))
    val vn = extracted.get(versionName in (prj,Android))
    val sdk = extracted.get(targetSdkVersion in (prj,Android))
    val minSdk = extracted.get(minSdkVersion in (prj,Android))
    val merge = extracted.get(mergeManifests in (prj,Android))

    layout.bin.mkdirs()
    val output = layout.bin / "AndroidManifest.xml"
    if (isLib)
      layout.manifest
    else {
      bldr.mergeManifests(layout.manifest, Seq.empty[File],
        if (merge) libs else Seq.empty[LibraryDependency],
        pkg, vc getOrElse -1, vn orNull, minSdk.toString, sdk.toString, null,
        output.getAbsolutePath, null,
        if (isLib) ManifestMerger2.MergeType.LIBRARY else
          ManifestMerger2.MergeType.APPLICATION, ph,
        layout.bin / "manifestmerge-report.txt")
      if (noTestApk) {
         val top = XML.loadFile(output)
        val prefix = top.scope.getPrefix(ANDROID_NS)
        val application = top \ APPLICATION_TAG
        val usesLibraries = top \ APPLICATION_TAG \ USES_LIBRARY_TAG
        val instrument = top \ INSTRUMENTATION_TAG
        if (application.isEmpty) sys.error("no application node")
        val hasTestRunner = usesLibraries exists (
          _.attribute(ANDROID_NS, "name") exists (_ == TEST_RUNNER_LIB))

        val last = Some(top) map { top =>
          if  (!hasTestRunner) {
            val runnerlib = new PrefixedAttribute(
              prefix, "name", TEST_RUNNER_LIB, Null)
            val usesLib = new Elem(null, USES_LIBRARY_TAG, runnerlib, TopScope,
                                   minimizeEmpty = true)
            val u = top.copy(
              child = top.child.updated(top.child.indexOf(application.head),
                application.head.asInstanceOf[Elem].copy(
                  child = application.head.child ++ usesLib)))
            u
          } else top
        } map { top =>
          if (instrument.isEmpty) {
             val target = new PrefixedAttribute(prefix,
               "targetPackage", pkg, Null)
             val label = new PrefixedAttribute(prefix,
               "label", "Test Runner", target)
             val name = new PrefixedAttribute(
               prefix, "name", trunner, label)
             val instrumentation = new Elem(null,
               INSTRUMENTATION_TAG, name, TopScope, minimizeEmpty = true)
            val u = top.copy(child = top.child ++ instrumentation)
            u
          } else top
        }

        val writer = new java.io.FileWriter(output, false)
        XML.write(writer, last.get, "utf-8", true, null)
        writer.close()
      }
      output
    }
  }

  val rGeneratorTaskDef = ( builder
                          , projectLayout
                          , processManifest
                          , collectResources
                          , packageForR
                          , libraryProject
                          , libraryProjects
                          , streams
                          ) map {
    case (bldr, layout, manif, (assets, res), pkg, lib, libs, s) =>
    val cache = s.cacheDirectory
    val proguardTxt = (layout.bin / "proguard.txt").getAbsolutePath

    val inputs = (res ***).get ++ (assets ***).get ++ Seq(manif) filter (
        n => !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    // need to check output changes, otherwise compile fails after gen-idea
    FileFunction.cached(cache / "r-generator")(
        FilesInfo.lastModified, FilesInfo.hash) { (in,out) =>
      s.log.info("Processing resources")
      if (!res.exists)
        s.log.warn("No resources found at " + res.getAbsolutePath)
      aapt(bldr, manif, pkg, libs, lib, res, assets, null,
        layout.gen, proguardTxt, s.log)
      s.log.debug(
        "In modified: %s\nInRemoved: %s\nOut checked: %s\nOut modified: %s"
          format (in.modified, in.removed, out.checked, out.modified))
      (layout.gen ** "R.java" get) ++ (layout.gen ** "Manifest.java" get) toSet
    }(inputs.toSet).toSeq
  }

  def aapt(bldr: AndroidBuilder, manifest: File, pkg: String,
      libs: Seq[LibraryDependency], lib: Boolean,
      res: File, assets: File, resApk: String, gen: File, proguardTxt: String,
      logger: Logger) {

    gen.mkdirs()
    val options = new AaptOptions {
      override def getIgnoreAssets = null
      override def getNoCompress = null
      override def getFailOnMissingConfigEntry = false
    }
    val genPath = gen.getAbsolutePath
    val all = collectdeps(libs) ++ libs
    logger.debug("All libs: " + all)
    logger.debug("All packages: " + (all map { l =>
      XML.loadFile(l.getManifest).attribute("package").get(0).text
    }))
    logger.debug("packageForR: " + pkg)
    logger.debug("proguard.txt: " + proguardTxt)
    val aaptCommand = new AaptPackageProcessBuilder(manifest, options)
    if (res.isDirectory)
      aaptCommand.setResFolder(res)
    if (assets.isDirectory)
      aaptCommand.setAssetsFolder(assets)
    aaptCommand.setLibraries(all)
    aaptCommand.setPackageForR(pkg)
    aaptCommand.setResPackageOutput(resApk)
    aaptCommand.setSourceOutputDir(genPath)
    aaptCommand.setSymbolOutputDir(genPath)
    aaptCommand.setProguardOutput(proguardTxt)
    aaptCommand.setType(if (lib) VariantType.LIBRARY else VariantType.DEFAULT)
    aaptCommand.setDebuggable(createDebug)
    bldr.processResources(aaptCommand, true)
  }

  def collectdeps(libs: Seq[AndroidLibrary]): Seq[AndroidLibrary] = {
    val deps = libs map (_.getDependencies) flatten

    if (libs.isEmpty) Seq.empty else collectdeps(deps) ++ deps
  }

  val proguardConfigTaskDef = ( projectLayout
                              , sdkPath
                              , useSdkProguard
                              , streams) map {
    (layout, p, u, s) =>
    val proguardTxt     = layout.bin  / "proguard.txt"
    val proguardProject = layout.base / "proguard-project.txt"

    val base = if (!u) {
      IO.readLinesURL(resourceUrl("android-proguard.config"))
    } else {
      import SdkConstants._
      import File.{separator => S}
      val c1 = file(p + OS_SDK_TOOLS_FOLDER + FD_PROGUARD + S +
        FN_ANDROID_PROGUARD_FILE)

      if (c1.exists)
        IO.readLines(c1)
      else
        List.empty[String]
    }
    def lines(file: File): Seq[String] =
      if (file.exists) IO.readLines(file) else Seq.empty

    (base ++ lines(proguardProject) ++ lines(proguardTxt)).toSeq: Seq[String]
  }

  val dexMainFileClassesConfigTaskDef = ( projectLayout
                                        , dexMainFileClasses) map {
    (layout, mainDexClasses) =>
      val mainDexListTxt = (layout.bin / "maindexlist.txt").getAbsoluteFile
      val writer = new PrintWriter(mainDexListTxt)
      try {
        writer.write(mainDexClasses.mkString("\n"))
      } finally {
        writer.close()
      }
      mainDexListTxt
  }

  val dexInputsTaskDef = ( proguard
                         , proguardInputs
                         , proguardCache
                         , thisProjectRef
                         , retrolambdaEnable
                         , dexMulti
                         , binPath
                         , dependencyClasspath
                         , classesJar
                         , state
                         , streams) map {
    (progOut, in, progCache, prj, re, multiDex, b, deps, classJar, st, s) =>

      // TODO use getIncremental in DexOptions instead
      val proguardedDexMarker = b / ".proguarded-dex"

      val jarsToDex = progOut match {
        case Some(obfuscatedJar) =>
          IO.touch(proguardedDexMarker, setModified = false)
          Seq(obfuscatedJar)
        case None =>
          proguardedDexMarker.delete()
          def nonCachedDeps = deps filterNot { file => progCache exists (_.matches(file, st)) }
          val dexingDeps = if (multiDex) deps else nonCachedDeps
          dexingDeps.collect {
            // no proguard? then we still able dex scala with multidex
            case x if x.data.getName.startsWith("scala-library") && multiDex =>
              x.data.getCanonicalFile
            case x if x.data.getName.endsWith(".jar") =>
              x.data.getCanonicalFile
          } ++ in.proguardCache :+ classJar
      }

      val incrementalDex = createDebug && (progCache.isEmpty || !proguardedDexMarker.exists)

      val dexIn = jarsToDex.groupBy (_.getName).collect {
          case ("classes.jar", aarClassJar) => aarClassJar.distinct // guess aars by jar name?
          case (_, otherJars) => otherJars.head :: Nil // distinct jars by name?
        }.flatten.toSeq

      incrementalDex ->
        (if (re && RetrolambdaSupport.isAvailable)
          Seq(RetrolambdaSupport.process(b, dexIn, st, prj, s)) else dexIn)
  }

  val dexTaskDef = ( builder
                   , dexInputs
                   , dexMaxHeap
                   , dexMulti
                   , dexMainFileClassesConfig
                   , dexMinimizeMainFile
                   , dexAdditionalParams
                   , libraryProject
                   , binPath
                   , streams) map {
    case (bldr, (incr, inputs), xmx, multiDex, mainDexListTxt, minMainDex, additionalParams, lib, bin, s) =>
      val incremental = incr && !multiDex
      if (!incremental && inputs.exists(_.getName.startsWith("proguard-cache"))) {
        s.log.debug("Cleaning dex files for proguard cache and incremental dex")
        (bin * "*.dex" get) foreach (_.delete())
      }
      val options = new DexOptions {
        override def getIncremental = incr
        override def getJavaMaxHeapSize = xmx
        override def getPreDexLibraries = false
        override def getJumboMode = false
        override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
      }
      s.log.info(s"Generating dex, incremental=$incremental, multiDex=$multiDex")
      s.log.debug("Dex inputs: " + inputs)

      val tmp  = s.cacheDirectory / "dex"
      tmp.mkdirs()

      def minimalMainDexParam = if (minMainDex) "--minimal-main-dex" else ""
      val additionalDexParams = (additionalParams.toList :+ minimalMainDexParam).distinct.filterNot(_.isEmpty)

      bldr.convertByteCode(inputs filter (_.isFile), Seq.empty[File], bin,
        multiDex, multiDex, mainDexListTxt,
        options, additionalDexParams, tmp, incremental, !createDebug)
      bin
  }

  val proguardInputsTaskDef = ( useProguard
                              , proguardScala
                              , proguardCache
                              , proguardLibraries
                              , dependencyClasspath
                              , platformJars
                              , classesJar
                              , binPath
                              , state
                              , streams
                              ) map {
    case (u, s, pc, l, d, (p, x), c, b, stat, st) =>
    val cacheDir = st.cacheDirectory
    if (u) {
      val injars = d.filter { a =>
        val in = a.data
        (s || !in.getName.startsWith("scala-library")) &&
          !l.exists { i => i.getName == in.getName} &&
          in.isFile
      }.distinct :+ Attributed.blank(c)
      val extras = x map (f => file(f))

      if (createDebug && pc.nonEmpty) {
        st.log.debug("Proguard cache rules: " + pc)
        val deps = cacheDir / "proguard_deps"
        val out = cacheDir / "proguard_cache"

        deps.mkdirs()
        out.mkdirs()

        val filtered = injars filterNot (j => pc exists (_.matches(j, stat)))
        val indeps = filtered map {
          f => deps / (f.data.getName + "-" +
            Hash.toHex(Hash(f.data.getAbsolutePath)))
        }

        val todep = indeps zip filtered filter { case (dep,j) =>
          !dep.exists || dep.lastModified < j.data.lastModified
        }
        todep foreach { case (dep,j) =>
          st.log.info("Finding dependency references for: " +
            (j.get(moduleID.key) getOrElse j.data.getName))
          IO.write(dep, ReferenceFinder.references(j.data, pc) mkString "\n")
        }

        val alldeps = (indeps flatMap {
          dep => IO.readLines(dep) }).sortWith(_>_).distinct.mkString("\n")

        val allhash = Hash.toHex(Hash(alldeps))

        val cacheJar = out / ("proguard-cache-" + allhash + ".jar")

        ProguardInputs(injars, file(p) +: (extras ++ l), Some(cacheJar))
      } else ProguardInputs(injars, file(p) +: (extras ++ l))
    } else
      ProguardInputs(Seq.empty,Seq.empty)
  }

  val proguardTaskDef: Def.Initialize[Task[Option[File]]] =
      ( useProguard
      , useProguardInDebug
      , proguardConfig
      , proguardOptions
      , proguardCache
      , libraryProject
      , binPath
      , proguardInputs
      , streams
      ) map { case (p, d, c, o, pc, l, b, inputs, s) =>
    val cacheDir = s.cacheDirectory
    val jars = inputs.injars
    val libjars = inputs.libraryjars
    if (inputs.proguardCache exists (_.exists)) {
      s.log.info("[debug] cache hit, skipping proguard!")
      None
    } else if ((p && !createDebug && !l) || ((d && createDebug) && !l)) {
      val t = b / "classes.proguard.jar"
      if (jars exists { _.data.lastModified > t.lastModified }) {
        val injars = "-injars " + (jars map {
          _.data.getPath + "(!META-INF/**,!rootdoc.txt)"
        } mkString File.pathSeparator)
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
        import java.util.Properties
        val parser: ConfigurationParser = new ConfigurationParser(
          cfg.toArray[String], new Properties)
        parser.parse(config)
        new ProGuard(config).execute()
      } else {
        s.log.info(t.getName + " is up-to-date")
      }
      inputs.proguardCache foreach { f =>
        val cd = cacheDir / "proguard_cache_tmp"
        IO.delete(cd)
        s.log.info("Creating proguard cache: " + f.getName)
        IO.unzip(t, cd, { n: String => pc.exists (_ matches n) })
        IO.jar((PathFinder(cd) ***) pair rebase(cd, ""),
          f, new java.util.jar.Manifest)
        IO.delete(cd)
      }
      Option(t)
    } else None
  }

  case class TestListener(log: Logger) extends ITestRunListener {
    import com.android.ddmlib.testrunner.TestIdentifier
    private var _failures: Seq[TestIdentifier] = Seq.empty
    def failures = _failures

    type TestMetrics = java.util.Map[String,String]
    override def testRunStarted(name: String, count: Int) {
      log.info("testing %s (tests: %d)" format (name, count - 1))
    }

    override def testStarted(id: TestIdentifier) {
      if ("testAndroidTestCaseSetupProperly" != id.getTestName)
        log.info(" - " + id.getTestName)
    }

    override def testEnded(id: TestIdentifier, metrics: TestMetrics) {
      log.debug("finished: %s (%s)" format (id.getTestName, metrics))
    }

    override def testFailed(id: TestIdentifier, m: String) {
      log.error("failed: %s\n%s" format (id.getTestName, m))

      _failures = _failures :+ id
    }

    override def testRunFailed(msg: String) {
      log.error("Testing failed: " + msg)
    }

    override def testRunStopped(elapsed: Long) {
      log.info("test run stopped (%dms)" format elapsed)
    }

    override def testRunEnded(elapsed: Long, metrics: TestMetrics) {
      log.info("test run finished (%dms)" format elapsed)
      log.debug("run end metrics: " + metrics)
    }

    override def testAssumptionFailure(id: TestIdentifier, m: String) {
      log.error("assumption failed: %s\n%s" format (id.getTestName, m))

      _failures = _failures :+ id
    }

    override def testIgnored(test: TestIdentifier) {
      log.warn("ignored: %s" format test.getTestName)
    }
  }

  val testTaskDef = ( projectLayout
                    , thisProjectRef
                    , debugIncludesTests
                    , builder
                    , packageName
                    , libraryProjects
                    , classDirectory in Test
                    , externalDependencyClasspath in Compile
                    , externalDependencyClasspath in Test
                    , state
                    , streams) map {
    (layout, prj, noTestApk, bldr, pkg, libs, classes, clib, tlib, st, s) =>
    val extracted = Project.extract(st)
    val timeo = extracted.get(instrumentTestTimeout in (prj,Android))
    val targetSdk = extracted.get(targetSdkVersion in (prj,Android))
    val minSdk = extracted.get(minSdkVersion in (prj,Android))
    val sdk = extracted.get(sdkPath in (prj,Android))
    val runner = extracted.get(instrumentTestRunner in (prj,Android))
    val xmx = extracted.get(dexMaxHeap in (prj,Android))
    val cache = s.cacheDirectory
    val re = extracted.get(retrolambdaEnable in (prj,Android))

    val testManifest = layout.testSources / "AndroidManifest.xml"
    // TODO generate a test manifest if one does not exist
    val manifestFile = if (noTestApk || testManifest.exists) {
      testManifest
    } else {
      generateTestManifest(pkg, classes, runner)
    }

    if (!noTestApk) {
      classes.mkdirs()
      val manifest = XML.loadFile(manifestFile)
      val testPackage = manifest.attribute("package") get 0 text
      val instr = manifest \\ "instrumentation"
      val instrData = (instr map { i =>
        (i.attribute(ANDROID_NS, "name") map (_(0).text),
        i.attribute(ANDROID_NS, "targetPackage") map (_(0).text))
      }).headOption
      val trunner = instrData flatMap (
        _._1) getOrElse runner
      val tpkg = instrData flatMap (_._2) getOrElse pkg
      val processedManifest = classes / "AndroidManifest.xml"
      // profiling and functional test? false for now
      bldr.processTestManifest(testPackage, minSdk.toString, targetSdk.toString,
        tpkg, trunner, false, false, manifestFile, libs,
        processedManifest.getAbsoluteFile,
        cache / "processTestManifest")
      val options = new DexOptions {
        override def getIncremental = true
        override def getJumboMode = false
        override def getPreDexLibraries = false
        override def getJavaMaxHeapSize = xmx
        override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
      }
      val rTxt = classes / "R.txt"
      val dex = layout.bin / "dex-test"
      dex.mkdirs()
      val res = layout.bin / "resources-test.ap_"
      val apk = layout.bin / "instrumentation-test.ap_"

      if (!rTxt.exists) rTxt.createNewFile()
      aapt(bldr, processedManifest, testPackage, libs, false,
        layout.testRes, layout.testAssets,
        res.getAbsolutePath, classes, null, s.log)

      val deps = tlib filterNot (clib contains)
      val tmp = cache / "test-dex"
      tmp.mkdirs()
      val inputs = if (re && RetrolambdaSupport.isAvailable) {
        Seq(RetrolambdaSupport.process(classes, deps map (_.data), st, prj, s))
      } else {
        Seq(classes) ++ (deps map (_.data))
      }
      bldr.convertByteCode(inputs, Seq.empty[File],
        dex, false, false, null, options, Nil, tmp, false, !createDebug)

      val debugConfig = new DefaultSigningConfig("debug")
      debugConfig.initDebug()

      val opts = new PackagingOptions {
        def getExcludes = Set.empty[String]
        def getPickFirsts = Set.empty[String]
      }
      bldr.packageApk(res.getAbsolutePath, dex,
        Seq.empty[File], Seq.empty[File], null, null, null,
        createDebug, debugConfig, opts, apk.getAbsolutePath)
      s.log.info("Testing...")
      s.log.debug("Installing test apk: " + apk)
      installPackage(apk, sdk, s.log)
      try {
        runTests(sdk, testPackage, s, trunner, timeo)
      } finally {
        uninstallPackage(None, testPackage, sdk, s.log)
      }
    } else {
      runTests(sdk, pkg, s, runner, timeo)
    }
    ()
  }

  lazy val testOnlyTaskDef: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val layout = projectLayout.value
    val prj = thisProjectRef.value
    val noTestApk = debugIncludesTests.value
    val pkg = packageName.value
    val classes = (classDirectory in Test).value
    val st = state.value
    val s = streams.value
    val extracted = Project.extract(st)
    val timeo = extracted.get(instrumentTestTimeout in (prj,Android))
    val sdk = extracted.get(sdkPath in (prj,Android))
    val runner = extracted.get(instrumentTestRunner in (prj,Android))
    val testManifest = layout.testSources / "AndroidManifest.xml"
    val manifestFile = if (noTestApk || testManifest.exists) {
      testManifest
    } else {
      generateTestManifest(pkg, classes, runner)
    }
    val manifest = XML.loadFile(manifestFile)
    val testPackage = manifest.attribute("package") get 0 text
    val testSelection = Def.spaceDelimited().parsed.mkString(" ")
    val trunner = if(noTestApk) runner else {
      val instr = manifest \\ "instrumentation"
      (instr map { i =>
        (i.attribute(ANDROID_NS, "name") map (_(0).text),
          i.attribute(ANDROID_NS, "targetPackage") map (_(0).text))
      }).headOption flatMap ( _._1) getOrElse runner
    }
    runTests(sdk, testPackage, s, trunner, timeo, Some(testSelection))
  }

  private def generateTestManifest(pkg: String, classes: File,
    runner: String) = {
    val vn = new PrefixedAttribute(ANDROID_PREFIX, "versionName", "1.0", Null)
    val vc = new PrefixedAttribute(ANDROID_PREFIX, "versionCode", "1", vn)
    val pkgAttr = new UnprefixedAttribute("package",
      pkg + ".instrumentTest", vc)
    val ns = NamespaceBinding(ANDROID_PREFIX, ANDROID_NS, TopScope)

    val minSdk = new PrefixedAttribute(
      ANDROID_PREFIX, "minSdkVersion", "3", Null)
    val usesSdk = new Elem(null, "uses-sdk", minSdk, TopScope, minimizeEmpty = true)
    val runnerlib = new PrefixedAttribute(
      ANDROID_PREFIX, "name", TEST_RUNNER_LIB, Null)
    val usesLib = new Elem(null, USES_LIBRARY_TAG, runnerlib, TopScope, minimizeEmpty = true)
    val app = new Elem(null,
      APPLICATION_TAG, Null, TopScope, minimizeEmpty = false, usesSdk, usesLib)
    val name = new PrefixedAttribute(
      ANDROID_PREFIX, "name", runner, Null)
    val instrumentation = new Elem(null, INSTRUMENTATION_TAG, name, TopScope, minimizeEmpty = true)
    val manifest = new Elem(null,
      "manifest", pkgAttr, ns, minimizeEmpty = false, app, instrumentation)

    val manifestFile = classes / "GeneratedTestAndroidManifest.xml"
    val writer = new java.io.FileWriter(manifestFile, false)
    XML.write(writer, manifest, "utf-8", true, null)
    writer.close()
    manifestFile
  }

  private def runTests(sdk: String, testPackage: String,
      s: TaskStreams, runner: String, timeo: Int, testSelection: Option[String] = None) {
    val listener = TestListener(s.log)
    import com.android.ddmlib.testrunner.InstrumentationResultParser
    val p = new InstrumentationResultParser(testPackage, listener)
    val receiver = new IShellOutputReceiver() {
      val b = new StringBuilder
      override def addOutput(data: Array[Byte], off: Int, len: Int) =
        b.append(new String(data, off, len))
      override def flush() {
        p.processNewLines(b.toString().split("\\n").map(_.trim))
        b.clear()
      }
      override def isCancelled = false
    }
    val intent = testPackage + "/" + runner
    Commands.targetDevice(sdk, s.log) map { d =>
      val selection = testSelection map { "-e " + _ } getOrElse ""
      val command = "am instrument -r -w %s %s" format(selection, intent)
      s.log.debug("Executing [%s]" format command)
      val timeout = DdmPreferences.getTimeOut
      DdmPreferences.setTimeOut(timeo)
      d.executeShellCommand(command, receiver)
      DdmPreferences.setTimeOut(timeout)

      if (receiver.b.toString().length > 0)
        s.log.info(receiver.b.toString())

      s.log.debug("instrument command executed")
    }
    if (listener.failures.nonEmpty) {
      sys.error("Tests failed: " + listener.failures.size + "\n" +
        (listener.failures map (" - " + _)).mkString("\n"))
    }
  }

  def runTaskDef(install: TaskKey[Unit],
      sdkPath: SettingKey[String],
      layout: SettingKey[ProjectLayout],
      packageName: SettingKey[String]) = inputTask { result =>
    (install, sdkPath, layout, packageName, result, streams) map {
      (_, k, l, p, r, s) =>

      val manifestXml = l.bin / "AndroidManifest.xml"
      val m = XML.loadFile(manifestXml)
      // if an arg is specified, try to launch that
      (if (r.isEmpty) None else Some(r(0))) orElse ((m \\ "activity") find {
        // runs the first-found activity
        a => (a \ "intent-filter") exists { filter =>
          val attrpath = "@{%s}name" format ANDROID_NS
          (filter \\ attrpath) exists (_.text == "android.intent.action.MAIN")
        }
      } map { activity =>
        val name = activity.attribute(ANDROID_NS, "name") get 0 text

        "%s/%s" format (p, if (name.indexOf(".") == -1) "." + name else name)
      }) map { intent =>
        val receiver = new IShellOutputReceiver() {
          val b = new StringBuilder
          override def addOutput(data: Array[Byte], off: Int, len: Int) =
            b.append(new String(data, off, len))
          override def flush() {
            s.log.info(b.toString())
            b.clear()
          }
          override def isCancelled = false
        }
        Commands.targetDevice(k, s.log) map { d =>
          val command = "am start -n %s" format intent
          s.log.debug("Executing [%s]" format command)
          d.executeShellCommand(command, receiver)

          if (receiver.b.toString().length > 0)
            s.log.info(receiver.b.toString())

          s.log.debug("run command executed")
        }
      } getOrElse {
        sys.error(
          "No activity found with action 'android.intent.action.MAIN'")
      }

      ()
    }
  }

  val KB = 1024 * 1.0
  val MB = KB * KB
  val installTaskDef = ( packageT
                       , libraryProject
                       , sdkPath
                       , streams) map {
    (p, l, k, s) =>

    if (!l) {
      Commands.targetDevice(k, s.log) foreach { d =>
        val cacheName = "install-" + URLEncoder.encode(
          d.getSerialNumber, "utf-8")
        FileFunction.cached(s.cacheDirectory / cacheName, FilesInfo.hash) { in =>
          s.log.info("Installing...")
          installPackage(p, k, s.log)
          in
        }(Set(p))
      }
    }
  }

  def installPackage(apk: File, sdkPath: String, log: Logger) {
    val start = System.currentTimeMillis
    Commands.targetDevice(sdkPath, log) foreach { d =>
      Option(d.installPackage(apk.getAbsolutePath, true)) map { err =>
        sys.error("Install failed: " + err)
      } getOrElse {
        val size = apk.length
        val end = System.currentTimeMillis
        val secs = (end - start) / 1000d
        val rate = size/KB/secs
        val mrate = if (rate > MB) rate / MB else rate
        log.info("[%s] Install finished: %s in %.2fs. %.2f%s/s" format (
          apk.getName, sizeString(size), secs, mrate,
          if (rate > MB) "MB" else "KB"))
      }
    }
  }

  def uninstallPackage(cacheDir: Option[File], packageName: String, sdkPath: String, log: Logger) {
    Commands.targetDevice(sdkPath, log) foreach { d =>
      val cacheName = "install-" + URLEncoder.encode(
        d.getSerialNumber, "utf-8")
      cacheDir foreach { c =>
        FileFunction.cached(c / cacheName, FilesInfo.hash) { in =>
          Set.empty
        }(Set.empty)
      }
      Option(d.uninstallPackage(packageName)) map { err =>
        sys.error("[%s] Uninstall failed: %s" format (packageName, err))
      } getOrElse {
        log.info("[%s] Uninstall finished" format packageName)
      }
    }
  }
  private def sizeString(len: Long) = {
    val KB = 1024 * 1.0
    val MB = KB * KB
    len match {
      case s if s < MB  => "%.2fKB" format (s/KB)
      case s if s >= MB => "%.2fMB" format (s/MB)
    }
  }
  val uninstallTaskDef = (sdkPath, packageName, streams) map { (k,p,s) =>
    uninstallPackage(Some(s.cacheDirectory), p, k, s.log)
  }

  def loadLibraryReferences(b: File, p: Properties, prefix: String = ""):
  Seq[AutoLibraryProject] = {
    (p.stringPropertyNames.collect {
        case k if k.startsWith("android.library.reference") => k
      }.toList.sortWith { (a,b) => a < b } map { k =>
        AutoLibraryProject(b/p(k)) +:
          loadLibraryReferences(b/p(k), loadProperties(b/p(k)), k)
      } flatten) distinct
  }

  def loadProperties(path: File): Properties = {
    val p = new Properties
    (path * "*.properties" get) foreach { f =>
      Using.file(new FileInputStream(_))(f) { p.load }
    }
    p
  }

  val unmanagedJarsTaskDef = ( unmanagedJars in Compile
                             , baseDirectory
                             , libraryProjects in Android, streams) map {
    (u, b, l, s) =>

    // remove scala-library if present
    // add all dependent library projects' classes.jar files
    (u ++ (l filterNot {
        case _: ApkLibrary         => true
        case _: AutoLibraryProject => true
        case _ => false
      } map { p => Attributed.blank(p.getJarFile.getCanonicalFile)
    }) ++ (for {
        d <- l
        j <- d.getLocalJars
      } yield Attributed.blank(j.getCanonicalFile)) ++ (for {
        d <- Seq(b / "libs", b / "lib")
        j <- d * "*.jar" get
      } yield Attributed.blank(j.getCanonicalFile))
    ) filter { !_.data.getName.startsWith("scala-library") }
  }
}
