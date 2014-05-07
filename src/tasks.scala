package android

import sbt._
import sbt.Keys._
import classpath.ClasspathUtilities

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._
import scala.util.control.Exception._
import scala.xml._

import java.util.Properties
import java.io.{File,FileInputStream}

import com.android.SdkConstants
import com.android.builder.model.{PackagingOptions, AaptOptions}
import com.android.builder.signing.{KeystoreHelper, DefaultSigningConfig}
import com.android.builder.AndroidBuilder
import com.android.builder.DexOptions
import com.android.builder.VariantConfiguration
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
import Dependencies._
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
  var _createDebug = true

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
  private def createDebug_=(d: Boolean) = _createDebug = d

  def resourceUrl =
    Plugin.getClass.getClassLoader.getResource _

  val buildConfigGeneratorTaskDef = ( platformTarget
                                    , genPath
                                    , libraryProjects
                                    , packageForR
                                    ) map {
    (t, g, l, p) =>
    val b = new BuildConfigGenerator(g.getAbsolutePath, p)
    b.addField("boolean", "DEBUG", createDebug.toString)
    b.generate()
    l collect {
      case a: ApkLibrary         => a
      case a: AutoLibraryProject => a
    } foreach { lib =>
      val b = new BuildConfigGenerator(g.getAbsolutePath, lib.pkg)
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
  def moduleString(m: ModuleID) =
    m.organization + ":" + m.name + ":" + m.revision

  // fails poorly if windows' exclusive locks are preventing a proper clean
  val aarsTaskDef = ( update in Compile
                    , localAars
                    , libraryDependencies in Compile
                    , target
                    , streams
                    ) map {
    (u,local,d,t,s) =>
    val libs = u.matching(artifactFilter(`type` = "aar"))
    val dest = t / "aars"
    val deps = d.map(moduleString).toSet
    (libs flatMap { l =>
      val m = moduleForFile(u, l)
      if (deps(moduleString(m))) {
        val d = dest / (m.organization + "-" + m.name + "-" + m.revision)
        Some(unpackAar(l, d, s.log): LibraryDependency)
      } else {
        None
      }
    }) ++ (local map { a =>
      unpackAar(a, dest / ("localAAR-" + a.getName), s.log)
    })
  }

  def unpackAar(aar: File, dest: File, log: Logger): LibraryDependency = {
    if (dest.lastModified < aar.lastModified) {
      dest.delete()
      log.info("Unpacking aar: %s to %s" format (aar.getName, dest.getName))
      dest.mkdirs()
      IO.unzip(aar, dest)
    }
    // rename for sbt-idea when using multiple aar packages
    val lib = AarLibrary(dest)
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
        IO.copy(((src ***) --- (src ** "R.txt")) x Path.rebase(src, dst),
          false, true)
      }
      if (isLib)
        copyDirectory(lib.layout.gen, gen)
      Some(lib: LibraryDependency)
    }
  }
  // fails poorly if windows' exclusive locks are preventing a proper clean
  val apklibsTaskDef = ( update in Compile
                       , libraryDependencies in Compile
                       , genPath
                       , libraryProject
                       , target
                       , streams
                       , builder
                       , streams
                       ) map {
    (u,d,gen,isLib,t,s,bldr,st) =>
    val libs = u.matching(artifactFilter(`type` = "apklib"))
    val dest = t / "apklibs"
    val deps = d.map(moduleString).toSet
    libs flatMap { l =>
      val m = moduleForFile(u, l)
      if (deps(moduleString(m))) {
        val d = dest / (m.organization + "-" + m.name + "-" + m.revision)
        val lib = ApkLibrary(d)
        if (d.lastModified < l.lastModified) {
          s.log.info("Unpacking apklib: " + m)
          d.mkdirs()
          IO.unzip(l, d)

          aapt(bldr, lib.getManifest, null, Seq.empty, true,
              lib.getResFolder, lib.getAssetsFolder, null,
              lib.layout.gen, lib.getProguardRules.getAbsolutePath,
              st.log)

        }
        def copyDirectory(src: File, dst: File) {
          IO.copy(((src ***) --- (src ** "R.txt")) x Path.rebase(src, dst),
            false, true)
        }
        if (isLib)
          copyDirectory(lib.layout.gen, gen)
        Some(lib: LibraryDependency)
      } else {
        s.log.info(m + " is not a part of " + deps)
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
                                       , cacheDirectory
                                       , libraryProjects
                                       , typedResourcesIgnores
                                       , streams
                                       ) map {
    case (t, a, p, layout, (j, x), sv, c, l, i, s) =>

    val r = layout.res
    val b = layout.base
    val g = layout.gen
    val ignores = i.toSet

    val tr = p.split("\\.").foldLeft (g) { _ / _ } / "TR.scala"

    if (!t)
      Seq.empty[File]
    else
      (FileFunction.cached(c / "typed-resources-generator",
          FilesInfo.hash, FilesInfo.hash) { in =>
        if (!in.isEmpty) {
          s.log.info("Regenerating TR.scala because R.java has changed")
          val androidjar = ClasspathUtilities.toLoader(file(j))
          val layouts = (r ** "layout*" ** "*.xml" get) ++
            (for {
              lib <- l filterNot {
                case p: Pkg => ignores(p.pkg)
                case _      => false
              }
              xml <- (lib.getResFolder) ** "layout*" ** "*.xml" get
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
      })(a.toSet).toSeq
  }

  val collectJniTaskDef = ( libraryProject
                          , libraryProjects
                          , projectLayout
                          , streams
                        ) map {
    (isLib, libs, layout, s) =>
    val jni = layout.bin / "jni"
    if (!isLib) {
      jni.mkdirs()
      // TODO traverse entire library dependency graph, goes 2 deep currently
      Seq(LibraryProject(layout.base)) ++ libs ++ libs.flatMap { l =>
        l.getDependencies map { _.asInstanceOf[LibraryDependency] }
      } collect {
        case r if r.layout.jni.isDirectory => r.layout.jni
      } foreach { j =>
        val copyList = (j ** "*.so" get) map { l =>
          (l, jni / (l relativeTo j).get.getPath)
        }
        IO.copy(copyList)
      }
    }

    jni
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
    val res = Seq(layout.res) ++ (libs map { _.layout.res } filter {
      _.isDirectory })

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
                                , cacheDirectory
                                , streams
                                ) map {
    (bldr, noTestApk, isLib, libs, layout, logger, cache, s) =>
      doCollectResources(bldr, noTestApk, isLib, libs, layout, logger, cache, s)
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
        val writer = new MergedResourceWriter(resTarget, bldr.getAaptRunner)
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
    val writer = new MergedResourceWriter(resTarget, bldr.getAaptRunner)
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
      (PathFinder(manifest)                 x flat) ++
      (PathFinder(javaSource) ** "*.java"   x rebase(javaSource,  "src")) ++
      (PathFinder(scalaSource) ** "*.scala" x rebase(scalaSource, "src")) ++
      ((PathFinder(res) ***)                x rebase(res,         "res")) ++
      ((PathFinder(assets) ***)             x rebase(assets,      "assets"))
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
    s.log.info("Packaging " + outfile.getName)
    val mapping =
      (PathFinder(manifest)             x flat) ++
      (PathFinder(gen / "R.txt")        x flat) ++
      (PathFinder(bin / "proguard.txt") x flat) ++
      (PathFinder(j)                    x flat) ++
      ((PathFinder(res) ***)            x rebase(res,    "res")) ++
      ((PathFinder(assets) ***)         x rebase(assets, "assets"))
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
                                , cacheDirectory
                                , streams
                                ) map {
    case (bldr, layout, manifest, (assets, res), pkg, lib, libs, cache, s) =>
    val proguardTxt = (layout.bin / "proguard.txt").getAbsolutePath

    val rel = if (createDebug) "-debug" else "-release"
    val basename = "resources" + rel + ".ap_"
    val p = layout.bin / basename

    val inputs = (res ***).get ++ (assets ***).get ++
      Seq(manifest)filter (
        n => !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    (FileFunction.cached(cache / basename, FilesInfo.hash) { _ =>
      s.log.info("Packaging resources: " + p.getName)
      aapt(bldr, manifest, pkg, libs, lib, res, assets,
        p.getAbsolutePath, layout.gen, proguardTxt, s.log)
      Set(p)
    })(inputs.toSet)
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

  val apkbuildTaskDef = ( builder
                        , state
                        , thisProjectRef
                        , packageResources
                        , dex
                        , unmanagedJars in Compile
                        , managedClasspath
                        , dependencyClasspath in Compile
                        , collectJni
                        , apkbuildExcludes
                        , streams
                        ) map {
    (bldr, st, prj, r, d, u, m, dcp, jni, excl, s) =>
    val extracted = Project.extract(st)
    import extracted._

    val n = get(name in prj)
    val layout = get(projectLayout in (prj, Android))
    val cacheDir = get(cacheDirectory in prj)
    val logger = get(ilogger in (prj, Android))

    val jars = (m ++ u ++ dcp).filter {
      a => (a.get(moduleID.key) map { mid =>
        mid.organization != "org.scala-lang"
      } getOrElse true) && a.data.exists
    }.groupBy(_.data.getName).collect {
      case ("classes.jar",xs) => xs.distinct
      case (_,xs) => xs.head :: Nil
    }.flatten.map (_.data).toList

    s.log.debug("jars to process for resources: " + jars)

    val debugConfig = new DefaultSigningConfig("debug")
    debugConfig.initDebug()
    if (!debugConfig.getStoreFile.exists) {
      KeystoreHelper.createDebugStore(debugConfig, logger(s.log))
    }

    val rel = if (createDebug) "-debug-unaligned.apk"
      else "-release-unsigned.apk"
    val pkg = n + rel
    val output = layout.bin / pkg

    // filtering out org.scala-lang above should not cause an issue
    // they should not be changing on us anyway
    val deps = Set(r +: d +: jars:_*)


    (FileFunction.cached(cacheDir / pkg, FilesInfo.hash) { in =>
      s.log.debug("bldr.packageApk(%s, %s, %s, null, %s, %s, %s, %s)" format (
        r.getAbsolutePath, d.getAbsolutePath, jars,
          jni.getAbsolutePath, createDebug,
          if (createDebug) debugConfig else null, output.getAbsolutePath
      ))
      val options = new PackagingOptions {
        def getExcludes = excl.toSet.asJava
      }
      bldr.packageApk(r.getAbsolutePath, d.getAbsolutePath, jars,
        layout.resources.getAbsolutePath,
        Seq(jni.getAbsoluteFile), null, createDebug,
        if (createDebug) debugConfig else null, options, output.getAbsolutePath)
      s.log.info("Packaged: %s (%s)" format (
        output.getName, sizeString(output.length)))
      Set(output)
    })(deps)

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
                            , targetSdkVersion
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
      script <- (layout.renderscript ** "*.rs" get)
    } yield (layout.renderscript,script)

    val generated = layout.gen ** "*.java" get

    val target = if (t < 11) "11" else t.toString

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
        "-o", (layout.bin / "resources" / "res" / "raw").getAbsolutePath) :+
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
                               ) map {
    (bldr, noTestApk, layout, isLib, libs, pkg, st, prj, trunner) =>
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
      bldr.processManifest(layout.manifest, Seq.empty[File],
        if (merge) libs else Seq.empty[LibraryDependency],
        pkg, vc getOrElse -1, vn getOrElse null, minSdk, sdk,
        output.getAbsolutePath)
      if (noTestApk) {
         val top = XML.loadFile(output)
        val prefix = top.scope.getPrefix(ANDROID_NS)
        val application = top \ APPLICATION_TAG
        val usesLibraries = top \ APPLICATION_TAG \ USES_LIBRARY_TAG
        val instrument = top \ INSTRUMENTATION_TAG
        if (application.isEmpty) sys.error("no application node")
        val hasTestRunner = usesLibraries exists (
          _.attribute(ANDROID_NS, "name") map (
            _ == TEST_RUNNER_LIB) getOrElse false)

        val last = Some(top) map { top =>
          if  (!hasTestRunner) {
            val runnerlib = new PrefixedAttribute(
              prefix, "name", TEST_RUNNER_LIB, Null)
            val usesLib = new Elem(null, USES_LIBRARY_TAG, runnerlib, TopScope)
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
               INSTRUMENTATION_TAG, name, TopScope)
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
                          , cacheDirectory
                          , streams
                          ) map {
    case (bldr, layout, manifest, (assets, res), pkg, lib, libs, cache, s) =>
    val proguardTxt = (layout.bin / "proguard.txt").getAbsolutePath

    val inputs = (res ***).get ++ (assets ***).get ++ Seq(manifest) filter (
        n => !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    // need to check output changes, otherwise compile fails after gen-idea
    FileFunction.cached(cache / "r-generator")(
        FilesInfo.lastModified, FilesInfo.hash) { (in,out) =>
      s.log.info("Generating R.java")
      aapt(bldr, manifest, pkg, libs, lib, res, assets, null,
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
    }
    val genPath = gen.getAbsolutePath
    val all = collectdeps(libs) ++ libs
    logger.debug("All libs: " + all)
    logger.debug("All packages: " + (all map { l =>
      XML.loadFile(l.getManifest).attribute("package").get(0).text
    }))
    logger.debug("packageForR: " + pkg)
    logger.debug("proguard.txt: " + proguardTxt)
    bldr.processResources(manifest, res, assets, all,
      pkg, genPath, genPath, resApk, proguardTxt,
      if (lib) VariantConfiguration.Type.LIBRARY else
        VariantConfiguration.Type.DEFAULT, createDebug, options, Seq.empty[String])
  }

  def collectdeps(libs: Seq[AndroidLibrary]): Seq[AndroidLibrary] = {
    val deps = libs map (_.getDependencies) flatten

    if (libs.isEmpty) Seq.empty else (collectdeps(deps) ++ deps)
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

      IO.readLines(c1)
    }
    def lines(file: File): Seq[String] =
      if (file.exists) IO.readLines(file) else Seq.empty

    (base ++ lines(proguardProject) ++ lines(proguardTxt)).toSeq: Seq[String]
  }

  val dexInputsTaskDef = ( proguard
                         , proguardInputs
                         , proguardCache
                         , binPath
                         , dependencyClasspath
                         , classesJar
                         , classesDex
                         , state
                         , streams) map {
    (p, i, pc, b, d, j, cd, st, s) =>

    // TODO use getIncremental in DexOptions instead
    val proguardedDexMarker = b / ".proguarded-dex"
    (p map { f =>
      IO.touch(proguardedDexMarker, false)
      Seq(f)
    } getOrElse {
      if (createDebug && proguardedDexMarker.exists) {
        s.log.info("[debug] Forcing clean dex")
        cd.delete() // force a clean dex on first non-proguard dex run
        proguardedDexMarker.delete()
      }
      if (!createDebug && cd.exists) {
        s.log.info("[release] Forcing clean dex")
        cd.delete() // always force a clean dex in release builds
      }
      (d filterNot { a => pc exists (_.matches(a, st)) } collect {
        // no proguard? then we don't need to dex scala!
        case x if !x.data.getName.startsWith("scala-library") &&
          x.data.getName.endsWith(".jar") => x.data.getCanonicalFile
      }) ++ i.proguardCache :+ j
    }).groupBy (_.getName).collect {
      case ("classes.jar",xs) => xs.distinct
      case (_,xs) => xs.head :: Nil
    }.flatten.toSeq
  }

  val dexTaskDef = ( builder
                   , dexInputs
                   , dexMaxHeap
                   , dexCoreLibrary
                   , libraryProject
                   , classesDex
                   , streams) map {
    (bldr, inputs, xmx, cl, lib, outDex, s) =>
    if (inputs exists { _.lastModified > outDex.lastModified }) {
      val options = new DexOptions {
        def isCoreLibrary = cl
        def getIncremental = true
        def getJavaMaxHeapSize = xmx
        def getPreDexLibraries = false
        def getJumboMode = false
      }
      s.log.info("Generating " + outDex.getName)
      s.log.debug("Dex inputs: " + inputs)
      bldr.convertByteCode(Seq.empty[File] ++
        inputs filter (_.isFile), Seq.empty[File],
        outDex.getAbsoluteFile, options, true)
    } else {
      s.log.info(outDex.getName + " is up-to-date")
    }
    outDex
  }

  val proguardInputsTaskDef = ( useProguard
                              , proguardScala
                              , proguardCache
                              , proguardLibraries
                              , dependencyClasspath
                              , platformJars
                              , classesJar
                              , binPath
                              , cacheDirectory
                              , state
                              , streams
                              ) map {
    case (u, s, pc, l, d, (p, x), c, b, cacheDir, stat, st) =>

    if (u) {
      val injars = d.filter { a =>
        val in = a.data
        (s || !in.getName.startsWith("scala-library")) &&
          !l.exists { i => i.getName == in.getName}
      }.distinct :+ Attributed.blank(c)
      val extras = x map (f => file(f))

      if (s && createDebug) {
        st.log.debug("Proguard cache rules: " + pc)
        val deps = (cacheDir / "proguard_deps")
        val out = (cacheDir / "proguard_cache")

        deps.mkdirs()
        out.mkdirs()

        val filtered = injars filterNot (j => pc exists (_.matches(j, stat)))
        val indeps = filtered map {
          f => deps / (f.data.getName + "-" +
            Hash.toHex(Hash(f.data.getAbsolutePath)))
        }

        val todep = indeps zip filtered filter { case (d,j) =>
          !d.exists || d.lastModified < j.data.lastModified
        }
        todep foreach { case (d,j) =>
          st.log.info("Finding dependency references for: " +
            (j.get(moduleID.key) getOrElse j.data.getName))
          IO.write(d, ReferenceFinder.references(j.data, pc) mkString "\n")
        }

        val alldeps = (indeps flatMap {
          d => IO.readLines(d) }).sortWith(_>_).distinct.mkString("\n")

        val allhash = Hash.toHex(Hash(alldeps))

        val cacheJar = out / ("proguard-cache-" + allhash + ".jar")

        ProguardInputs(injars, file(p) +: (extras ++ l), Some(cacheJar))
      } else ProguardInputs(injars, file(p) +: (extras ++ l))
    } else
      ProguardInputs(Seq.empty,Seq.empty)
  }

  val proguardTaskDef: Project.Initialize[Task[Option[File]]] =
      ( useProguard
      , useProguardInDebug
      , proguardConfig
      , proguardOptions
      , proguardCache
      , libraryProject
      , binPath
      , cacheDirectory
      , proguardInputs
      , streams
      ) map { case (p, d, c, o, pc, l, b, cacheDir, inputs, s) =>
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
        } mkString(File.pathSeparator))
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
        val parser: ConfigurationParser = new ConfigurationParser(
          cfg.toArray[String], new Properties)
        parser.parse(config)
        new ProGuard(config).execute
      } else {
        s.log.info(t.getName + " is up-to-date")
      }
      inputs.proguardCache foreach { f =>
        val cd = cacheDir / "proguard_cache_tmp"
        IO.delete(cd)
        s.log.info("Creating proguard cache: " + f.getName)
        IO.unzip(t, cd, { n: String => pc.exists (_ matches n) })
        IO.jar((PathFinder(cd) ***) x rebase(cd, ""),
          f, new java.util.jar.Manifest)
        IO.delete(cd)
      }
      Option(t)
    } else None
  }

  case class TestListener(log: Logger) extends ITestRunListener {
    import com.android.ddmlib.testrunner.TestIdentifier
    import com.android.ddmlib.testrunner.ITestRunListener.TestFailure
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

    override def testFailed(fail: TestFailure, id: TestIdentifier, m: String) {
      log.error("%s: %s\n%s" format (fail, id.getTestName, m))

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
  }

  val testTaskDef = ( projectLayout
                    , instrumentTestTimeout
                    , debugIncludesTests
                    , builder
                    , packageName
                    , libraryProjects
                    , classDirectory in Test
                    , externalDependencyClasspath in Compile
                    , externalDependencyClasspath in Test
                    , state
                    , streams) map {
    (layout, timeo, noTestApk, bldr, pkg, libs, classes, clib, tlib, st, s) =>
    val extracted = Project.extract(st)
    val targetSdk = extracted.get(targetSdkVersion in Android)
    val minSdk = extracted.get(minSdkVersion in Android)
    val sdk = extracted.get(sdkPath in Android)
    val runner = extracted.get(instrumentTestRunner in Android)
    val xmx = extracted.get(dexMaxHeap in Android)
    val cl  = extracted.get(dexCoreLibrary in Android)

    val testManifest = layout.testSources / "AndroidManifest.xml"
    // TODO generate a test manifest if one does not exist
    val manifestFile = if (noTestApk || testManifest.exists) {
      testManifest
    } else {
      val vn = new PrefixedAttribute(ANDROID_PREFIX, "versionName", "1.0", Null)
      val vc = new PrefixedAttribute(ANDROID_PREFIX, "versionCode", "1", vn)
      val pkgAttr = new UnprefixedAttribute("package",
        pkg + ".instrumentTest", vc)
      val ns = NamespaceBinding(ANDROID_PREFIX, ANDROID_NS, TopScope)

      val minSdk = new PrefixedAttribute(
        ANDROID_PREFIX, "minSdkVersion", "3", Null)
      val usesSdk = new Elem(null, "uses-sdk", minSdk, TopScope)
      val runnerlib = new PrefixedAttribute(
        ANDROID_PREFIX, "name", TEST_RUNNER_LIB, Null)
      val usesLib = new Elem(null, USES_LIBRARY_TAG, runnerlib, TopScope)
      val app = new Elem(null,
        APPLICATION_TAG, Null, TopScope, usesSdk, usesLib)
      val label = new PrefixedAttribute(
          ANDROID_PREFIX, "label", "Test Runner", Null)
      val name = new PrefixedAttribute(
        ANDROID_PREFIX, "name", runner, label)
      val instrumentation = new Elem(null, INSTRUMENTATION_TAG, name, TopScope)
      val manifest = new Elem(null,
        "manifest", pkgAttr, ns, app, instrumentation)

      val manifestFile = classes / "GeneratedTestAndroidManifest.xml"
      val writer = new java.io.FileWriter(manifestFile, false)
      XML.write(writer, manifest, "utf-8", true, null)
      writer.close()
      manifestFile
    }

    if (!noTestApk) {
      classes.mkdirs()
      val manifest = XML.loadFile(manifestFile)
      val testPackage = manifest.attribute("package") get (0) text
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
      bldr.processTestManifest(testPackage, minSdk, targetSdk, tpkg, trunner,
        false, false, libs, processedManifest.getAbsolutePath)
      val options = new DexOptions {
        def isCoreLibrary = cl
        def getIncremental = true
        def getJumboMode = false
        def getPreDexLibraries = false
        def getJavaMaxHeapSize = xmx
      }
      val rTxt = classes / "R.txt"
      val dex = layout.bin / "classes-test.dex"
      val res = layout.bin / "resources-test.ap_"
      val apk = layout.bin / "instrumentation-test.ap_"

      if (!rTxt.exists) rTxt.createNewFile()
      aapt(bldr, processedManifest, testPackage, libs, false,
        layout.testRes, layout.testAssets,
        res.getAbsolutePath, classes, null, s.log)

      val deps = tlib filterNot (clib contains)
      bldr.convertByteCode(Seq(classes) ++ (deps map (_.data)), Seq.empty[File],
        dex.getAbsoluteFile, options, false)

      if (!dex.exists)
        sys.error("No test classes (no dex)")
      val debugConfig = new DefaultSigningConfig("debug")
      debugConfig.initDebug

      val opts = new PackagingOptions {
        def getExcludes = Set.empty[String]
      }
      bldr.packageApk(res.getAbsolutePath, dex.getAbsolutePath,
        Seq.empty[File], null, null, null, createDebug, debugConfig,
        opts, apk.getAbsolutePath)
      s.log.info("Testing...")
      s.log.debug("Installing test apk: " + apk)
      installPackage(apk, sdk, s.log)
      try {
        runTests(sdk, testPackage, s, trunner, timeo)
      } finally {
        uninstallPackage(testPackage, sdk, s.log)
      }
    } else {
      runTests(sdk, pkg, s, runner, timeo)
    }
    ()
  }

  private def runTests(sdk: String, testPackage: String,
      s: TaskStreams, runner: String, timeo: Int) {
    val listener = TestListener(s.log)
    import com.android.ddmlib.testrunner.InstrumentationResultParser
    val p = new InstrumentationResultParser(testPackage, listener)
    val receiver = new IShellOutputReceiver() {
      val b = new StringBuilder
      override def addOutput(data: Array[Byte], off: Int, len: Int) =
        b.append(new String(data, off, len))
      override def flush() {
        p.processNewLines(b.toString.split("\\n").map(_.trim))
        b.clear
      }
      override def isCancelled = false
    }
    val intent = testPackage + "/" + runner
    Commands.targetDevice(sdk, s.log) map { d =>
      val command = "am instrument -r -w %s" format intent
      s.log.debug("Executing [%s]" format command)
      val timeout = DdmPreferences.getTimeOut()
      DdmPreferences.setTimeOut(timeo)
      d.executeShellCommand(command, receiver)
      DdmPreferences.setTimeOut(timeout)

      if (receiver.b.toString.length > 0)
        s.log.info(receiver.b.toString)

      s.log.debug("instrument command executed")
    }
    if (!listener.failures.isEmpty) {
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
        Commands.targetDevice(k, s.log) map { d =>
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

  val KB = 1024 * 1.0
  val MB = KB * KB
  val installTaskDef = ( packageT
                       , libraryProject
                       , sdkPath
                       , cacheDirectory
                       , streams) map {
    (p, l, k, c, s) =>

    if (!l) {
      Commands.targetDevice(k, s.log) foreach { d =>
        val cacheName = "install-" + URLEncoder.encode(
          d.getSerialNumber, "utf-8")
        FileFunction.cached(c / cacheName, FilesInfo.hash) { in =>
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
        val size = apk.length;
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

  def uninstallPackage(packageName: String, sdkPath: String, log: Logger) {
    Commands.targetDevice(sdkPath, log) foreach { d =>
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
    uninstallPackage(p, k, s.log)
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
      Using.file(new FileInputStream(_))(f) { p.load _ }
    }
    p
  }

  val unmanagedJarsTaskDef = ( unmanagedJars in Compile
                             , baseDirectory
                             , libraryProjects in Android, streams) map {
    (u, b, l, s) =>

    // remove scala-library if present
    // add all dependent library projects' classes.jar files
    (u ++ (l filterNot (_ match {
        case _: ApkLibrary         => true
        case _: AutoLibraryProject => true
        case _ => false
      }) map { p => Attributed.blank((p.getJarFile).getCanonicalFile)
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
