package android

import sbt._
import sbt.Keys._
import classpath.ClasspathUtilities

import scala.io.Source
import scala.collection.JavaConversions._
import scala.util.control.Exception._
import scala.xml.{XML, Elem}

import java.util.Properties
import java.io.{File,FilenameFilter,FileInputStream}

import com.android.SdkConstants
import com.android.builder.model.AaptOptions
import com.android.builder.signing.DefaultSigningConfig
import com.android.builder.AndroidBuilder
import com.android.builder.DefaultSdkParser
import com.android.builder.DexOptions
import com.android.builder.VariantConfiguration
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}
import com.android.ddmlib.{IDevice, IShellOutputReceiver}
import com.android.ddmlib.testrunner.ITestRunListener
import com.android.ide.common.res2.FileStatus
import com.android.ide.common.res2.FileValidity
import com.android.ide.common.res2.MergedResourceWriter
import com.android.ide.common.res2.ResourceMerger
import com.android.ide.common.res2.ResourceSet
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.BuildToolInfo.PathId
import com.android.utils.ILogger
import com.android.utils.StdLogger

import proguard.{Configuration => PgConfig, ProGuard, ConfigurationParser}

import Keys._
import Dependencies._

object Tasks {
  val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  // TODO come up with a better solution
  // wish this could be protected
  var _createDebug = true

  def createDebug = _createDebug
  private def createDebug_=(d: Boolean) = _createDebug = d

  def resourceUrl =
    Plugin.getClass.getClassLoader.getResource _

  val buildConfigGeneratorTaskDef = ( builder
                                    , platformTarget
                                    , genPath
                                    , libraryProjects
                                    , packageForR
                                    ) map {
    (b, t, g, l, p) =>
    b.generateBuildConfig(p, createDebug,
      Seq.empty[String], g.getAbsolutePath)
    l collect { case a: ApkLibrary => a } foreach { lib =>
      b.generateBuildConfig(lib.pkg, createDebug,
        Seq.empty[String], g.getAbsolutePath)
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
                    , libraryDependencies in Compile
                    , target
                    , streams
                    ) map {
    (u,d,t,s) =>
    val libs = u.matching(artifactFilter(`type` = "aar"))
    val dest = t / "aars"
    val deps = d.map(moduleString).toSet
    libs flatMap { l =>
      val m = moduleForFile(u, l)
      if (deps(moduleString(m))) {
        val d = dest / (m.organization + "-" + m.name + "-" + m.revision)
        if (d.lastModified < l.lastModified) {
          s.log.info("Unpacking aar: " + m)
          d.mkdirs()
          IO.unzip(l, d)
        }
        val lib = AarLibrary(d)
        Some(lib: LibraryDependency)
      } else {
        None
      }
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
                                       , scalaVersion
                                       , cacheDirectory
                                       , libraryProjects
                                       , streams
                                       ) map {
    case (t, a, p, layout, (j, x), sv, c, l, s) =>

    val r = layout.res
    val b = layout.base
    val g = layout.gen

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
              lib <- l
              xml <- (lib.path) ** "layout*" ** "*.xml" get
            } yield xml)

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
          IO.write(tr, trTemplate format (p, implicitsImport,
            resources map { case (k,v) =>
              "  val `%s` = TypedResource[%s](R.id.`%s`)" format (k,v,k)
            } mkString "\n",
            layoutTypes map { case (k,v) =>
              "    val `%s` = TypedLayout[%s](R.layout.`%s`)" format (k,v,k)
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
      libs ++ libs.flatMap { l =>
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
  val collectResourcesTaskDef = ( builder
                                , libraryProject
                                , libraryProjects
                                , projectLayout
                                , ilogger
                                , cacheDirectory
                                , streams
                                ) map {
    (bldr, isLib, libs, layout, logger, cache, s) =>

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

    // prepare resource sets for merge
    val res = Seq(layout.res) ++ (libs map { _.layout.res } filter {
        _.isDirectory })

    // this needs to wait for other projects to at least finish their
    // apklibs tasks--handled if androidBuild() is called properly
    val depres = collectdeps(libs) collect {
      case m: ApkLibrary => m
      case n: AarLibrary => n
    } collect { case n if n.getResFolder.isDirectory => n.getResFolder }

    val respaths = depres ++ res.reverse ++
      (if (layout.res.isDirectory) Seq(layout.res) else Seq.empty)
    val sets = respaths map { r =>
      val set = new ResourceSet(r.getAbsolutePath)
      set.addSource(r)
      set
    }

    val inputs = (respaths map { r => (r ***) get } flatten) filter (n =>
      !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    FileFunction.cached(cache / "collect-resources")(
      FilesInfo.hash, FilesInfo.exists) { (inChanges,outChanges) =>
      s.log.info("Collecting resources")
      incrResourceMerge(layout.base, resTarget, isLib, libs,
      cache / "collect-resources", logger(s.log), bldr, sets, inChanges, s.log)
      (resTarget ***).get.toSet
    }(inputs toSet)

    (assetBin, resTarget)
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
          if (!fileValidity.getDataSet.updateWith(
              fileValidity.getSourceFile, file, status, logger)) {
            slog.debug("Unable to handle changed file: " + file)
            merge()
            true
          } else
            false
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
    val genPath = layout.gen.getAbsolutePath

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
                        , collectJni
                        , streams
                        ) map {
    (bldr, st, prj, r, d, u, m, jni, s) =>
    val extracted = Project.extract(st)
    import extracted._

    val n = get(name in prj)
    val layout = get(projectLayout in (prj, Android))
    val cacheDir = get(cacheDirectory in prj)

    val jars = (m ++ u).filter(_.data.exists).groupBy(_.data.getName).collect {
      case ("classes.jar",xs) => xs.distinct
      case (_,xs) => xs.head :: Nil
    }.flatten map (_.data) toList

    val debugConfig = new DefaultSigningConfig("debug")
    debugConfig.initDebug()

    val rel = if (createDebug) "-debug-unaligned.apk"
      else "-release-unsigned.apk"
    val pkg = n + rel
    val output = layout.bin / pkg

    val deps = Set(r +: d +: jars:_*)


    (FileFunction.cached(cacheDir / pkg, FilesInfo.hash) { in =>
      bldr.packageApk(r.getAbsolutePath, d.getAbsolutePath, jars, null,
        jni.getAbsolutePath, createDebug,
        if (createDebug) debugConfig else null, output.getAbsolutePath)
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
                               , projectLayout
                               , libraryProject
                               , libraryProjects
                               , packageName
                               , versionCode
                               , versionName
                               , targetSdkVersion
                               , minSdkVersion
                               ) map {
    (bldr, layout, isLib, libs, pkg, vc, vn, sdk, minSdk) =>
    layout.bin.mkdirs()
    val output = layout.bin / "AndroidManifest.xml"
    if (isLib)
      layout.manifest
    else {
      bldr.processManifest(layout.manifest, Seq.empty[File], libs,
        pkg, vc getOrElse -1, vn getOrElse null, minSdk, sdk,
        output.getAbsolutePath)
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
        FilesInfo.hash, FilesInfo.hash) { (in,out) =>
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
        VariantConfiguration.Type.DEFAULT, createDebug, options)
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

  def dedupeClasses(bin: File, jars: Seq[File]): Seq[File] = {
      // only attempt to dedupe if jars with the same name are found
      if (jars.groupBy(_.getName) exists { case (k,v) => v.size > 1 }) {
        val combined = bin / "all-classes.jar"
        val combined_tmp = bin / "all_tmp"
        if (jars exists (_.lastModified > combined.lastModified)) {
          IO.createDirectory(combined_tmp)
          val files = (jars filter (_.isFile)).foldLeft (Set.empty[File]) {
            (acc,j) =>
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
                         , proguardInputs
                         , binPath
                         , dependencyClasspath
                         , classesJar
                         , streams) map {
    (p, i, b, d, j, s) =>

    (p map { f => Seq(f) } getOrElse {
      (d collect {
        // no proguard? then we don't need to dex scala!
        case x if !x.data.getName.startsWith("scala-library") &&
          x.data.getName.endsWith(".jar") => x.data.getCanonicalFile
      }) ++ i.scalaCache :+ j
    }).groupBy (_.getName).collect {
      case ("classes.jar",xs) => xs.distinct
      case (_,xs) => xs.head :: Nil
    }.flatten.toSeq
  }

  val dexTaskDef = ( builder
                   , dexInputs
                   , libraryProject
                   , classesDex
                   , streams) map {
    (bldr, inputs, lib, outDex, s) =>
    if (inputs exists { _.lastModified > outDex.lastModified }) {
      val options = new DexOptions {
        def isCoreLibrary = false
        def getIncremental = true
      }
      s.log.info("Generating " + outDex.getName)
      s.log.debug("Dex inputs: " + inputs)
      bldr.convertByteCode(Seq.empty[File],
        inputs filter (_.isFile), null, outDex.getAbsolutePath, options, true)
    } else {
      s.log.info(outDex.getName + " is up-to-date")
    }
    outDex
  }

  val proguardInputsTaskDef = ( useProguard
                              , proguardScala
                              , proguardLibraries
                              , dependencyClasspath
                              , platformJars
                              , classesJar
                              , binPath
                              , cacheDirectory
                              , streams
                              ) map {
    case (u, s, l, d, (p, x), c, b, cacheDir, st) =>

    if (u) {
      val injars = (d map { _.data.getCanonicalFile }) :+ c filter { in =>
        (s || !in.getName.startsWith("scala-library")) &&
          !l.exists { i => i.getName == in.getName}
      } distinct
      val extras = x map (f => file(f))

      if (s && createDebug) {
        val deps = (cacheDir / "proguard_deps")
        val out = (cacheDir / "proguard_cache")

        deps.mkdirs()
        out.mkdirs()

        val indeps = injars map { f =>
          deps / (f.getName + "-" + Hash.toHex(Hash(f.getAbsolutePath)))
        }
        val todep = indeps zip injars filter { case (d,j) =>
          !d.exists || d.lastModified < j.lastModified
        }
        todep foreach { case (d,j) =>
          st.log.info("Finding scala dependencies for: " + j.getName)
          IO.write(d, ReferenceFinder.references(j) mkString "\n")
        }

        val alldeps = (indeps flatMap {
          d => IO.readLines(d) }).sortWith(_>_).distinct.mkString("\n")

        val allhash = Hash.toHex(Hash(alldeps))

        val cacheJar = out / ("proguard-scala-" + allhash + ".jar")

        ProguardInputs(injars,
          file(p) +: (extras ++ l), Some(cacheJar))
      } else ProguardInputs(injars, file(p) +: (extras ++ l))
    } else
      ProguardInputs(Seq.empty,Seq.empty)
  }

  val proguardTaskDef: Project.Initialize[Task[Option[File]]] =
      ( useProguard
      , useProguardInDebug
      , proguardConfig
      , proguardOptions
      , libraryProject
      , binPath
      , cacheDirectory
      , proguardInputs
      , streams
      ) map { case (p, d, c, o, l, b, cacheDir, inputs, s) =>
    val jars = inputs.injars
    val libjars = inputs.libraryjars
    if (inputs.scalaCache exists (_.exists)) {
      s.log.info("[debug] Scala library already processed, skipping proguard")
      None
    } else if ((p && !createDebug && !l) || ((d && createDebug) && !l)) {
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
        val parser: ConfigurationParser = new ConfigurationParser(
          cfg.toArray[String], new Properties)
        parser.parse(config)
        new ProGuard(config).execute
      } else {
        s.log.info(t.getName + " is up-to-date")
      }
      inputs.scalaCache foreach { f =>
        val cd = cacheDir / "proguard_cache_tmp"
        IO.delete(cd)
        s.log.info("Creating scala cache: " + f.getName)
        IO.unzip(t, cd, "scala/**")
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
                    , builder
                    , packageName
                    , libraryProjects
                    , classDirectory in Test
                    , targetSdkVersion
                    , minSdkVersion
                    , sdkPath
                    , streams) map {
    (layout, bldr, pkg, libs, classes, targetSdk, minSdk, sdk, s) =>
    val testManifest = layout.testSources / "AndroidManifest.xml"
    if (testManifest.exists) {
      classes.mkdirs()
      val manifest = XML.loadFile(testManifest)
      val testPackage = manifest.attribute("package") get (0) text
      val instr = manifest \\ "instrumentation"
      val instrData = (instr map { i =>
        (i.attribute(ANDROID_NS, "name") map (_(0).text),
        i.attribute(ANDROID_NS, "targetPackage") map (_(0).text))
      }).headOption
      val runner = instrData flatMap (
        _._1) getOrElse "android.test.InstrumentationTestRunner"
      val tpkg = instrData flatMap (_._2) getOrElse pkg
      val processedManifest = classes / "AndroidManifest.xml"
      bldr.processTestManifest(testPackage, minSdk, targetSdk, tpkg, runner,
        libs, processedManifest.getAbsolutePath)
      val options = new DexOptions {
        def isCoreLibrary = false
        def getIncremental = true
      }
      val rTxt = classes / "R.txt"
      val dex = layout.bin / "classes-test.dex"
      val res = layout.bin / "resources-test.ap_"
      val apk = layout.bin / "instrumentation-test.ap_"

      if (!rTxt.exists) rTxt.createNewFile()
      aapt(bldr, processedManifest, testPackage, libs, false,
        layout.testSources / "res", layout.testSources / "assets",
        res.getAbsolutePath, classes, null, s.log)

      bldr.convertByteCode(Seq(classes), Seq.empty[File], null,
        dex.getAbsolutePath, options, false)

      if (!dex.exists)
        sys.error("No test classes (no dex)")
      val debugConfig = new DefaultSigningConfig("debug")
      debugConfig.initDebug

      bldr.packageApk(res.getAbsolutePath, dex.getAbsolutePath,
        Seq.empty[File], null, null, true, debugConfig, apk.getAbsolutePath)
      s.log.info("Testing...")
      s.log.debug("Installing test apk: " + apk)
      installPackage(apk, sdk, s.log)
      try {
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
          d.executeShellCommand(command, receiver)

          if (receiver.b.toString.length > 0)
            s.log.info(receiver.b.toString)

          s.log.debug("instrument command executed")
        }
        if (!listener.failures.isEmpty) {
          sys.error("Tests failed:\n" +
            (listener.failures map (" - " + _)).mkString("\n"))
        }
      } finally {
        uninstallPackage(testPackage, sdk, s.log)
      }
    } else {
      s.log.warn("No test manifest found")
    }
    ()
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
        val cacheName = "install-" + d.getSerialNumber
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
  Seq[LibraryProject] = {
      p.stringPropertyNames.collect {
        case k if k.startsWith("android.library.reference") => k
      }.toList.sortWith { (a,b) => a < b } map { k =>
        LibraryProject(b/p(k)) +:
          loadLibraryReferences(b, loadProperties(b/p(k)), k)
      } flatten
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
    (u ++ (l filter (!_.isInstanceOf[ApkLibrary]) map { p =>
      Attributed.blank((p.getJarFile).getCanonicalFile)
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
