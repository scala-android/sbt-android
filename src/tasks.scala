package android

import com.android.builder.internal.ClassFieldImpl
import com.android.ide.common.signing.KeystoreHelper
import com.android.manifmerger.ManifestMerger2
import sbt._
import sbt.Keys._
import sbt.classpath.ClasspathUtilities

import scala.collection.JavaConverters._
import scala.util.Try
import scala.xml._
import language.postfixOps

import java.util.Properties
import java.io.File

import com.android.SdkConstants
import com.android.builder.signing.DefaultSigningConfig
import com.android.builder.core._
import com.android.ddmlib.{IDevice, DdmPreferences}
import com.android.ddmlib.testrunner.ITestRunListener
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.BuildToolInfo.PathId
import com.android.sdklib.build.RenderScriptProcessor
import com.android.sdklib.build.RenderScriptProcessor.CommandLineLauncher

import Keys._
import Keys.Internal._
import Dependencies.{LibraryProject => _, AutoLibraryProject => _, _}
import com.android.builder.compiling.{ResValueGenerator, BuildConfigGenerator}
import java.net.URLEncoder

import BuildOutput._

object Tasks {
  val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  val TOOLS_NS = "http://schemas.android.com/tools"
  val INSTRUMENTATION_TAG = "instrumentation"
  val USES_LIBRARY_TAG = "uses-library"
  val APPLICATION_TAG = "application"
  val ANDROID_PREFIX = "android"
  val TEST_RUNNER_LIB = "android.test.runner"

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

  def resourceUrl =
    Plugin.getClass.getClassLoader.getResource _

  val resValuesGeneratorTaskDef = Def.task {
    implicit val output = outputLayout.value
    val resources = resValues.value
    val items = resources map { case (typ, n, value) =>
      new ClassFieldImpl(typ, n, value)
    }
    val resTarget = projectLayout.value.generatedRes
    if (items.nonEmpty) {
      val generator = new ResValueGenerator(resTarget)
      generator.addItems((items: Seq[Object]).asJava)
      generator.generate()
    } else {
      IO.delete(resTarget / "values" / "generated.xml")
    }
  }

  val buildConfigGeneratorTaskDef = ( platformTarget
                                    , projectLayout
                                    , libraryProjects
                                    , packageForR
                                    , buildConfigOptions
                                    , apkbuildDebug
                                    ) map {
    (t, layout, l, p, o, d) =>
    val b = new BuildConfigGenerator(layout.gen, p)
    b.addField("boolean", "DEBUG", d.toString)
    o foreach {
      case (tpe, n, value) => b.addField(tpe, n, value)
    }
    b.generate()
    l collect {
      case a: ApkLibrary         => a
      case a: AutoLibraryProject => a
    } foreach { lib =>
      val b = new BuildConfigGenerator(layout.gen, lib.pkg)
      b.addField("boolean", "DEBUG", d.toString)
      o foreach {
        case (tpe, n, value) => b.addField(tpe, n, value)
      }
      b.generate()
    }
    layout.gen ** "BuildConfig.java" get
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
                    , transitiveAndroidWarning
                    , localProjects
                    , thisProjectRef
                    , state
                    , buildStructure
                    , projectLayout
                    , streams
                    ) map {
    (u,local,d,tx,tw,lp,ref, st, struct, layout,s) =>
    implicit val output = Project.extract(st).get(outputLayout in (ref,Android))

    def collectSubAar(ps: Seq[ProjectRef]): Seq[AarLibrary] = ps flatMap { p =>
      val aarlibs = Project.runTask(libraryProjects in(p, Android), st).collect {
        case (_, Value(value)) => value
      }.fold(Seq.empty[AarLibrary])(_ collect {
        case a: AarLibrary => a
      })
      val sub = Project.getProject(p, struct)
      aarlibs ++ sub.fold(Seq.empty[AarLibrary])(s => collectSubAar(s.dependencies.map(_.project)))
    }
    val subaars = collectSubAar(
      Project.getProject(ref, struct).fold(
        Seq.empty[ProjectRef])(_.dependencies map (_.project))).map (m =>
      moduleString(m.moduleID)).toSet
    s.log.debug("dependencies: " + Project.getProject(ref, struct).map(_.dependencies))
    s.log.debug("aars in subprojects: " + subaars)
    val libs = u.matching(artifactFilter(`type` = "aar"))
    val dest = layout.aars

    val deps = d.filterNot(_.configurations.exists(
      _ contains "test")).map(moduleString).toSet
    (libs flatMap { l =>
      val m = moduleForFile(u, l)
      if (tx || deps(moduleString(m))) {
        val d = dest / (m.organization + "-" + m.name + "-" + m.revision)
        if (!subaars(moduleString(m)))
          Some(unpackAar(l, d, m, s.log): LibraryDependency)
        else {
          s.log.debug(m + " was already included by a dependent project, skipping")
          None
        }
      } else {
        if (tw)
          s.log.warn(m + " is not an explicit dependency, skipping")
        None
      }
    }) ++ (local map { a =>
      unpackAar(a, dest / ("localAAR-" + a.getName), "localAAR" % a.getName % "LOCAL", s.log)
    })
  }

  def unpackAar(aar: File, dest: File, m: ModuleID, log: Logger): LibraryDependency = {
    val lib = AarLibrary(dest)
    if (dest.lastModified < aar.lastModified || !lib.getManifest.exists) {
      IO.delete(dest)
      val mfile = Dependencies.moduleIdFile(dest)
      val mline = s"${m.organization}:${m.name}:${m.revision}"
      IO.writeLines(mfile, mline :: Nil)
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
                        , projectLayout
                        , outputLayout
                        , libraryProject
                        , builder
                        , ilogger
                        , apkbuildDebug
                        , streams ) map {
   (prjs,layout,o, isLib,bldr,logger,debug,s) =>
     implicit val output = o
     prjs collect { case a: AutoLibraryProject => a } flatMap { lib =>
      s.log.info("Processing library project: " + lib.pkg)

      lib.getProguardRules.getParentFile.mkdirs
      // TODO collect resources from apklibs and aars
//      doCollectResources(bldr, true, true, Seq.empty, lib.layout,
//        logger, file("/"), s)
      Resources.aapt(bldr, lib.getManifest, null, Seq.empty, true, debug(),
          lib.getResFolder, lib.getAssetsFolder, null,
          lib.layout.gen, lib.getProguardRules.getAbsolutePath,
          s.log)

      def copyDirectory(src: File, dst: File) {
        IO.copy(((src ***) --- (src ** "R.txt")) pair Path.rebase(src, dst),
          false, true)
      }
      if (isLib)
        copyDirectory(lib.layout.gen, layout.gen)
      Some(lib: LibraryDependency)
    }
  }

  val apklibsTaskDef = ( update in Compile
                       , libraryDependencies in Compile
                       , projectLayout
                       , outputLayout
                       , libraryProject
                       , transitiveAndroidLibs
                       , streams
                       , builder
                       , apkbuildDebug
                       , streams
                       ) map {
    (u,d,layout,o, isLib,tx,s,bldr,debug,st) =>
    implicit val output = o
    val libs = u.matching(artifactFilter(`type` = "apklib"))
    val dest = layout.apklibs
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

          Resources.aapt(bldr, lib.getManifest, null, Seq.empty, true, debug(),
              lib.getResFolder, lib.getAssetsFolder, null,
              lib.layout.gen, lib.getProguardRules.getAbsolutePath,
              st.log)

        }
        def copyDirectory(src: File, dst: File) {
          IO.copy(((src ***) --- (src ** "R.txt")) pair Path.rebase(src, dst),
            false, true)
        }
        if (isLib)
          copyDirectory(lib.layout.gen, layout.gen)
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
                pkg => Try(androidjar.loadClass(pkg + l).getName).toOption
              }.headOption
            }
          }

          def warn(res: Seq[(String,String)]) = {
            // nice to have:
            //   merge to a common ancestor, this is possible for androidJar
            //   but to do so is perilous/impossible for project code...
            // instead:
            //   reduce to ViewGroup for *Layout, and View for everything else
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
          def wrap(s: String) = if (reservedWords(s)) "`%s`" format s else s
          IO.write(tr, trTemplate format (p,
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

  def ndkbuild(layout: ProjectLayout, ndkHome: Option[String],
               srcs: File, log: Logger, debug: Boolean)(implicit m: ProjectLayout => BuildOutput) = {
    val hasJni = (layout.jni ** "Android.mk" get).nonEmpty
    if (hasJni) {
      if (ndkHome.isEmpty) {
        log.warn(
          "Android.mk found, but neither ndk.dir nor ANDROID_NDK_HOME are set")
      }
      ndkHome flatMap { ndk =>
        val env = Seq("NDK_PROJECT_PATH" -> (layout.jni / "..").getAbsolutePath,
          "NDK_OUT" -> layout.ndkObj.getAbsolutePath,
          "NDK_LIBS_OUT" -> layout.ndkBin.getAbsolutePath,
          "SBT_SOURCE_MANAGED" -> srcs.getAbsolutePath)

        log.info("Executing NDK build")
        val ndkbuildFile = if (!Commands.isWindows) file(ndk) / "ndk-build" else  {
          val f = file(ndk) / "ndk-build.cmd"
          if (f.exists) f else file(ndk) / "ndk-build.bat"
        }
        val ndkBuildInvocation = Seq(
          ndkbuildFile.getAbsolutePath,
          if (debug) "NDK_DEBUG=1" else "NDK_DEBUG=0"
        )

        val rc = Process(ndkBuildInvocation, layout.base, env: _*) !

        if (rc != 0)
          Plugin.fail("ndk-build failed!")

        Option(layout.ndkBin)
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
    val natives = NativeFinder(classes)

    if (natives.nonEmpty) {
      val javah = Seq("javah",
        "-d", src.getAbsolutePath,
        "-classpath", cp map (_.data.getAbsolutePath) mkString File.pathSeparator,
        "-bootclasspath", bldr.getBootClasspath.asScala mkString File.pathSeparator) ++ natives

      s.log.debug(javah mkString " ")

      val rc = javah !

      if (rc != 0)
        Plugin.fail("Failed to execute: " + (javah mkString " "))

      src ** "*.h" get
    } else Seq.empty
  }
  val ndkBuildTaskDef = ( projectLayout
                        , outputLayout
                        , libraryProjects
                        , sourceManaged in Compile
                        , ndkJavah
                        , ndkPath
                        , properties
                        , streams
                        , apkbuildDebug
                        ) map { (layout, o, libs, srcs, h, ndkHome, p, s, debug) =>
    implicit val output = o
    val subndk = libs flatMap { l =>
      ndkbuild(l.layout, ndkHome, srcs, s.log, debug()).toSeq
    }

    ndkbuild(layout, ndkHome, srcs, s.log, debug()).toSeq ++ subndk
  }

  val collectProjectJniTaskDef = Def.task {
    val layout = projectLayout.value
    implicit val output = outputLayout.value
    ndkBuild.value ++ Seq(layout.jniLibs, layout.rsLib).filter(_.isDirectory)
  }

  val collectJniTaskDef = Def.task {
    def libJni(lib: LibraryDependency): Seq[File] =
      Seq(lib.getJniFolder).filter(_.isDirectory) ++
        lib.getDependencies.asScala.flatMap(l => libJni(l.asInstanceOf[LibraryDependency]))

    collectProjectJni.value ++ libraryProjects.value.flatMap(libJni)
  }


  val collectResourcesTaskDef = ( builder
                                , debugIncludesTests
                                , libraryProject
                                , libraryProjects
                                , extraResDirectories
                                , extraAssetDirectories
                                , projectLayout
                                , outputLayout
                                , ilogger
                                , streams
                                ) map {
    (bldr, noTestApk, isLib, libs, er, ea, layout, o, logger, s) =>
      implicit val output = o
      Resources.doCollectResources(bldr, noTestApk, isLib, libs, layout, ea, layout.generatedRes +: er, logger, s.cacheDirectory, s)
  }


  val packageApklibMappings = Def.task {
    val layout = projectLayout.value
    import layout._

    (PathFinder(manifest)                 pair flat) ++
    (PathFinder(javaSource) ** "*.java"   pair rebase(javaSource,  "src"))  ++
    (PathFinder(scalaSource) ** "*.scala" pair rebase(scalaSource, "src"))  ++
    ((PathFinder(libs) ***)               pair rebase(libs,        "libs")) ++
    ((PathFinder(res) ***)                pair rebase(res,         "res"))  ++
    ((PathFinder(assets) ***)             pair rebase(assets,      "assets"))
  }
  val packageApklibTaskDef = Def.task {
    implicit val output = outputLayout.value
    val outfile = projectLayout.value.outputApklibFile(name.value)
    streams.value.log.info("Packaging " + outfile.getName)
    val mapping = (mappings in packageApklib).value
    IO.jar(mapping, outfile, new java.util.jar.Manifest)
    outfile
  }

  val packageAarMappings = Def.task {
    implicit val output = outputLayout.value
    val layout = projectLayout.value
    import layout._

    val so = collectProjectJni.value
    val j = (packageT in Compile).value
    val rsLibs = layout.rsLib
    val rsRes = layout.rsRes

    (PathFinder(manifest)             pair flat) ++
    (PathFinder(layout.rTxt)          pair flat) ++
    (PathFinder(layout.proguardTxt)   pair flat) ++
    (PathFinder(j)                    pair flat) ++
    ((PathFinder(libs) ** "*.jar")    pair rebase(libs,   "libs")) ++
    ((PathFinder(rsLibs) * "*.jar")   pair rebase(rsLibs, "libs")) ++
    ((PathFinder(res) ***)            pair rebase(res,    "res"))  ++
    ((PathFinder(rsRes) ***)          pair rebase(rsRes,  "res"))  ++
    ((PathFinder(assets) ***)         pair rebase(assets, "assets")) ++
    so.flatMap { d => (PathFinder(d) ** "*.so") pair rebase(d, "jni") }
  }

  val packageAarTaskDef = Def.task {
    implicit val output = outputLayout.value
    val outfile = projectLayout.value.outputAarFile(name.value)
    val mapping = (mappings in packageAar).value
    streams.value.log.info("Packaging " + outfile.getName)

    IO.jar(mapping, outfile, new java.util.jar.Manifest)
    outfile
  }

  val packageResourcesTaskDef = ( builder
                                , projectLayout
                                , outputLayout
                                , processManifest
                                , collectResources
                                , packageForR
                                , libraryProject
                                , libraryProjects
                                , apkbuildDebug
                                , streams
                                ) map {
    case (bldr, layout, output, manif, (assets, res), pkg, lib, libs, debug, s) =>
    val cache = s.cacheDirectory
    implicit val o = output
    val proguardTxt = layout.proguardTxt.getAbsolutePath
    layout.proguardTxt.getParentFile.mkdirs()

    val p = layout.resApk(debug())

    val inputs = (res ***).get ++ (assets ***).get ++
      Seq(manif)filter (
        n => !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    FileFunction.cached(cache / p.getName, FilesInfo.hash) { _ =>
      s.log.info("Packaging resources: " + p.getName)
      Resources.aapt(bldr, manif, pkg, libs, lib, debug(), res, assets,
        p.getAbsolutePath, layout.gen, proguardTxt, s.log)
      Set(p)
    }(inputs.toSet)
    p
  }

  val apkbuildAggregateTaskDef = Def.task {
    Aggregate.Apkbuild(packagingOptions.value,
      apkbuildDebug.value(), dex.value, predex.value,
      collectJni.value, resourceShrinker.value)
  }

  val apkbuildTaskDef = Def.task {
    implicit val output = outputLayout.value
    val layout = projectLayout.value
    val a = apkbuildAggregate.value
    val n = name.value
    val u = (unmanagedJars in Compile).value
    val m = managedClasspath.value
    val dcp = (dependencyClasspath in Compile).value
    val s = streams.value
    val logger = ilogger.value(s.log)
    Packaging.apkbuild(builder.value, m, u, dcp, libraryProject.value,
      a.packagingOptions, a.resourceShrinker, a.dex, a.predex, a.collectJni,
      layout.collectJni, layout.resources, a.apkbuildDebug,
      layout.unsignedApk(a.apkbuildDebug, n), logger, s)
  }

  val signReleaseTaskDef = (apkSigningConfig, apkbuild, apkbuildDebug, projectLayout, outputLayout, streams) map {
    (c, a, d, l, o, s) =>
    implicit val output = o
    if (d()) {
      s.log.info("Debug package does not need signing: " + a.getName)
      a
    } else {
      c map { cfg =>
        import SignJar._
        val signed = l.signedApk(a)
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

  val zipalignTaskDef = (zipalignPath, signRelease, apkFile, projectLayout, outputLayout, streams) map {
    (z, r, a, l, o, s) =>
    implicit val output = o
    if (r.getName.contains("-unsigned")) {
      s.log.warn("Package needs signing and zipaligning: " + r.getName)
      a.delete()
      r
    } else {
      val aligned = l.alignedApk(r)

      val rv = Seq(z, "-f", "4", r.getAbsolutePath, aligned.getAbsolutePath) !

      if (rv != 0) {
        Plugin.fail("zipalign failed")
      }

      s.log.info("zipaligned: " + aligned.getName)
      a.getParentFile.mkdirs()
      IO.copyFile(aligned, a)
      aligned
    }
  }

  val renderscriptTaskDef = Def.task {
    implicit val output = outputLayout.value
    val layout = projectLayout.value
    val scripts = (layout.renderscript ** "*.rs").get
    val target = Try(rsTargetApi.value.toInt).getOrElse(11) max 11

    val processor = new RenderScriptProcessor(
      scripts.asJava,
      List.empty.asJava,
      layout.rsBin,
      projectLayout.value.generatedSrc,
      layout.rsRes,
      layout.rsObj,
      layout.rsLib,
      buildTools.value,
      target,
      false, // debug flag -g, not used
      rsOptimLevel.value,
      rsSupportMode.value)

    processor.build(new CommandLineLauncher {
      override def launch(executable: sbt.File, arguments: java.util.List[String],
                          envVariableMap: java.util.Map[String, String]): Unit = {
        
        val cmd = executable.getAbsolutePath +: arguments.asScala
        streams.value.log.debug(s"renderscript command: $cmd, env: ${envVariableMap.asScala.toSeq}")

        val r = Process(cmd, None, envVariableMap.asScala.toSeq: _*).!
        if (r != 0)
          Plugin.fail("renderscript failed: " + r)
      }
    })

    if (rsSupportMode.value) { // copy support library
      val in = SdkLayout.renderscriptSupportLibFile(buildTools.value)
      val out = layout.rsLib
      IO.copy(
        (in * "*.jar" pair rebase(in, out)) ++
        (in / "packaged" ** "*.so" pair rebase(in / "packaged", out))
      )
    }

    val JavaSource = """\s*(\S+\.java).*""".r
    (layout.rsDeps ** "*.d").get
      .flatMap(IO.readLines(_))
      .collect { case JavaSource(path) => new File(path) }
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
          Plugin.fail("aidl failed")

        layout.gen ** (idl.getName.stripSuffix(".aidl") + ".java") get
      } else out
    }
  }

  val processManifestTaskDef = ( builder
                               , libraryProject
                               , libraryProjects
                               , manifestAggregate
                               , mergeManifests
                               , instrumentTestRunner
                               , projectLayout
                               , outputLayout
                               , debugIncludesTests
                               ) map {
    (bldr, isLib, libs, a, merge, trunner, layout, out, noTestApk) =>
    implicit val o = out
    val pkg = a.applicationId
    val ph = a.placeholders
    val vc = a.versionCode
    val vn = a.versionName
    val sdk = a.targetSdkVersion
    val minSdk = a.minSdkVersion

    layout.bin.mkdirs()
    val output = layout.processedManifest
    output.getParentFile.mkdirs()
    if (isLib)
      layout.manifest
    else {
      bldr.mergeManifests(layout.manifest, Seq.empty.asJava,
        if (merge) libs.asJava else Seq.empty.asJava,
        pkg, vc getOrElse -1, vn orNull, minSdk.toString, sdk.toString, null,
        output.getAbsolutePath, null,
        if (isLib) ManifestMerger2.MergeType.LIBRARY else
          ManifestMerger2.MergeType.APPLICATION, ph.asJava,
        layout.processedManifestReport)
      if (noTestApk) {
         val top = XML.loadFile(output)
        val prefix = top.scope.getPrefix(ANDROID_NS)
        val application = top \ APPLICATION_TAG
        val usesLibraries = top \ APPLICATION_TAG \ USES_LIBRARY_TAG
        val instrument = top \ INSTRUMENTATION_TAG
        if (application.isEmpty) Plugin.fail("no manifest application node")
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

        XML.save(output.getAbsolutePath, last.get, "utf-8", true, null)
      }
      output
    }
  }

  val rGeneratorTaskDef = ( builder
                          , projectLayout
                          , outputLayout
                          , processManifest
                          , collectResources
                          , packageForR
                          , libraryProject
                          , libraryProjects
                          , apkbuildDebug
                          , streams
                          ) map {
    case (bldr, layout, o, manif, (assets, res), pkg, lib, libs, debug, s) =>
    implicit val output = o
    val cache = s.cacheDirectory
    val proguardTxt = layout.proguardTxt.getAbsolutePath
    layout.proguardTxt.getParentFile.mkdirs()

    val inputs = (res ***).get ++ (assets ***).get ++ Seq(manif) filter (
        n => !n.getName.startsWith(".") && !n.getName.startsWith("_"))

    // need to check output changes, otherwise compile fails after gen-idea
    FileFunction.cached(cache / "r-generator")(
        FilesInfo.lastModified, FilesInfo.hash) { (in,out) =>
      s.log.info("Processing resources")
      if (!res.exists)
        s.log.warn("No resources found at " + res.getAbsolutePath)
      Resources.aapt(bldr, manif, pkg, libs, lib, debug(), res, assets, null,
        layout.gen, proguardTxt, s.log)
      s.log.debug(
        "In modified: %s\nInRemoved: %s\nOut checked: %s\nOut modified: %s"
          format (in.modified, in.removed, out.checked, out.modified))
      (layout.gen ** "R.java" get) ++ (layout.gen ** "Manifest.java" get) toSet
    }(inputs.toSet).toSeq
  }

  val proguardConfigTaskDef = ( projectLayout
                              , outputLayout
                              , sdkPath
                              , useSdkProguard
                              , aars
                              , streams) map {
    (layout, out, p, u, a, s) =>
    implicit val output = out
    val proguardTxt     = layout.proguardTxt
    val proguardProject = layout.proguard

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

    val aarConfig = a.flatMap { l =>
      val pc = l.path / "proguard.txt"
      if (pc.isFile) IO.readLines(pc) else Seq.empty
    }
    (base ++ lines(proguardProject) ++ lines(proguardTxt) ++ aarConfig).toSeq: Seq[String]
  }

  val dexMainClassesConfigTaskDef = Def.task {
    implicit val output = outputLayout.value
    Dex.dexMainClassesConfig(projectLayout.value, dexLegacyMode.value,
      dexMulti.value, dexInputs.value._2, dexMainClasses.value,
      buildTools.value, streams.value)
  }

  val retrolambdaAggregateTaskDef = Def.task {
    Aggregate.Retrolambda(retrolambdaEnabled.value,
      Project.extract(state.value).currentUnit.classpath, builder.value)
  }
  val dexInputsTaskDef = ( proguard
                         , proguardInputs
                         , proguardAggregate
                         , retrolambdaAggregate
                         , dexMulti
                         , projectLayout
                         , outputLayout
                         , dependencyClasspath
                         , apkbuildDebug
                         , streams) map {
    (progOut, in, pa, ra, multiDex, b, o, deps, debug, s) =>
      implicit val output = o
      Dex.dexInputs(progOut, in, pa, ra, multiDex, b.dex, deps, b.classesJar, debug(), s)
  }

  val manifestAggregateTaskDef = Def.task {
    Aggregate.Manifest(applicationId.value,
      versionName.value, versionCode.value,
      minSdkVersion.value, targetSdkVersion.value,
      manifestPlaceholders.value)
  }
  val dexAggregateTaskDef = Def.task {
    Aggregate.Dex(dexInputs.value, dexMaxHeap.value, dexMulti.value,
      dexMainClassesConfig.value, dexMinimizeMain.value,
      buildTools.value, dexAdditionalParams.value)
  }

  val dexTaskDef = ( builder
                   , dexAggregate
                   , dexShards
                   , predex
                   , proguard
                   , dexLegacyMode
                   , libraryProject
                   , projectLayout
                   , outputLayout
                   , apkbuildDebug
                   , streams) map {
    case (bldr, dexOpts, shards, pd, pg, legacy, lib, bin, o, debug, s) =>
      implicit val output = o
      Dex.dex(bldr, dexOpts, pd, pg, bin.classesJar, legacy, lib, bin.dex, shards, debug(), s)
  }

  val predexTaskDef = Def.task {
    implicit val output = outputLayout.value
    val opts = dexAggregate.value
    val inputs = opts.inputs._2
    val multiDex = opts.multi
    val shards = dexShards.value
    val legacy = dexLegacyMode.value
    val classes = projectLayout.value.classesJar
    val pg = proguard.value
    val bldr = builder.value
    val s = streams.value
    val bin = projectLayout.value.predex
    Dex.predex(opts, inputs, multiDex || shards, legacy, classes, pg, bldr, bin, s)
  }

  val proguardInputsTaskDef = ( proguardAggregate
                              , proguardLibraries
                              , dependencyClasspath
                              , platformJars
                              , projectLayout
                              , outputLayout
                              , apkbuildDebug
                              , streams
                              ) map {
    case (pa, l, d, (p, x), c, o, debug, st) =>
      implicit val output = o
      Proguard.proguardInputs(
        pa.useProguard, pa.proguardOptions, pa.proguardConfig,
        l, d, p, x, c.classesJar, pa.proguardScala, pa.proguardCache, debug(), st)
  }

  val resourceShrinkerTaskDef = Def.task {
    val jar = proguard.value
    implicit val out = outputLayout.value
    val resApk = packageResources.value
    val doShrink = shrinkResources.value
    val layout = projectLayout.value
    val log = streams.value.log
    import com.android.build.gradle.tasks.ResourceUsageAnalyzer
    if (jar.isDefined && doShrink && !ResourceUsageAnalyzer.TWO_PASS_AAPT) {
      val shrunkResApk = resApk.getParentFile / ("shrunk-" + resApk.getName)
      val resTarget = layout.mergedRes
      val analyzer = new ResourceUsageAnalyzer(
        layout.gen, jar.get, processManifest.value, null, resTarget)
      analyzer.analyze()
      analyzer.rewriteResourceZip(resApk, shrunkResApk)
      val unused = analyzer.getUnusedResourceCount
      if (unused > 0) {
        val before = resApk.length
        val after = shrunkResApk.length
        val pct = (before - after) * 100 / before
        log.info(s"Resource Shrinker: $unused unused resources")
        log.info(s"Resource Shrinker: data reduced from ${Packaging.sizeString(before)} to ${Packaging.sizeString(after)}, removed $pct%")
      }
      shrunkResApk
    } else {
      resApk
    }
  }

  val proguardAggregateTaskDef = Def.task {
    Aggregate.Proguard(useProguard.value, useProguardInDebug.value,
      proguardScala.value,
      proguardConfig.value, proguardOptions.value, proguardCache.value)
  }

  val proguardTaskDef: Def.Initialize[Task[Option[File]]] =
      ( proguardAggregate
      , builder
      , libraryProject
      , proguardInputs
      , apkbuildDebug
      , projectLayout
      , outputLayout
      , retrolambdaAggregate
      , streams
      ) map { case (a, bldr, l, inputs, debug, b, output, ra, s) =>
        implicit val o = output
        Proguard.proguard(a, bldr, l, inputs, debug(), b.proguardOut, ra, s)
  }

  case class TestListener(log: Logger) extends ITestRunListener {
    import com.android.ddmlib.testrunner.TestIdentifier
    private var _failures: Seq[TestIdentifier] = Seq.empty
    def failures = _failures

    type TestMetrics = java.util.Map[String,String]
    override def testRunStarted(name: String, count: Int) {
      log.info("testing %s (tests: %d)" format (name, count))
    }

    override def testStarted(id: TestIdentifier) {
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

  val testAggregateTaskDef = Def.task {
    Aggregate.AndroidTest(debugIncludesTests.value, instrumentTestRunner.value,
      instrumentTestTimeout.value, apkbuildDebug.value(), dexMaxHeap.value,
      (externalDependencyClasspath in Test).value map (_.data),
      (externalDependencyClasspath in Compile).value map (_.data),
      packagingOptions.value, libraryProject.value)
  }
  val testTaskDef = ( projectLayout
                    , outputLayout
                    , builder
                    , classDirectory
                    , sdkPath
                    , allDevices
                    , testAggregate
                    , manifestAggregate
                    , retrolambdaAggregate
                    , libraryProjects
                    , streams) map {
    (layout, o, bldr, classes, sdk, all, ta, ma, ra, libs, s) =>
    if (ta.libraryProject)
      Plugin.fail("This project cannot `android:test`, it has set 'libraryProject in Android := true")
    implicit val output = o
    val xmx = ta.dexMaxHeap
    val pkg = ma.applicationId
    val minSdk = ma.minSdkVersion
    val targetSdk = ma.targetSdkVersion
    val placeholders = ma.placeholders
    val timeo = ta.instrumentTestTimeout
    val noTestApk = ta.debugIncludesTests
    val runner = ta.instrumentTestRunner
    val clib = ta.externalDependencyClasspathInCompile
    val tlib = ta.externalDependencyClassPathInTest
    val cache = s.cacheDirectory
    val re = ra.enable
    val debug = ta.apkbuildDebug

    val testManifest = layout.testManifest
    val manifestFile = if (noTestApk || testManifest.exists) {
      testManifest
    } else {
      generateTestManifest(pkg, layout.processedTestManifest, runner)
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
      val processedManifest = layout.processedManifest
      // profiling and functional test? false for now
      bldr.processTestManifest(testPackage, minSdk.toString, targetSdk.toString,
        tpkg, trunner, false, false, manifestFile, libs.asJava,
        (placeholders: Map[String,Object]).asJava, processedManifest.getAbsoluteFile,
        cache / "processTestManifest")
      val options = new DexOptions {
        override def getIncremental = true
        override def getJumboMode = false
        override def getPreDexLibraries = false
        override def getJavaMaxHeapSize = xmx
        override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
      }
      val rTxt = layout.testRTxt
      val dex = layout.testDex
      dex.mkdirs()
      val res = layout.testResApk
      val apk = layout.testApk

      if (!rTxt.exists) rTxt.createNewFile()
      Resources.aapt(bldr, processedManifest, testPackage, libs, false, debug,
        layout.testRes, layout.testAssets,
        res.getAbsolutePath, classes, null, s.log)

      val deps = tlib filterNot (clib contains)
      val tmp = cache / "test-dex"
      tmp.mkdirs()
      val inputs = if (re && RetrolambdaSupport.isAvailable) {
        RetrolambdaSupport(classes, deps, ra.classpath, bldr, s)
      } else {
        Seq(classes) ++ deps
      }
      bldr.convertByteCode(inputs.asJava, List.empty.asJava,
        dex, false, null, options, List.empty.asJava, tmp, false, !debug)

      val debugConfig = new DefaultSigningConfig("debug")
      debugConfig.initDebug()

      val opts = ta.packagingOptions
      bldr.packageApk(res.getAbsolutePath, dex,
        List.empty.asJava, List.empty.asJava, null, null, s.cacheDirectory / "test-apkbuild-merge", null,
        debug, debugConfig, opts.asAndroid, apk.getAbsolutePath)
      s.log.debug("Installing test apk: " + apk)

      def install(device: IDevice) {
        installPackage(apk, sdk, device, s.log)
      }

      if (all)
        Commands.deviceList(sdk, s.log).par foreach install
      else
        Commands.targetDevice(sdk, s.log) foreach install

      try {
        runTests(sdk, testPackage, s, trunner, timeo, all)
      } finally {
        def uninstall(device: IDevice) {
          uninstallPackage(None, testPackage, sdk, device, s.log)
        }

        if (all)
          Commands.deviceList(sdk, s.log).par foreach uninstall
        else
          Commands.targetDevice(sdk, s.log) foreach uninstall
      }
    } else {
      runTests(sdk, pkg, s, runner, timeo, all)
    }
    ()
  }

  val testOnlyTaskDef: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val layout = projectLayout.value
    implicit val output = outputLayout.value
    val noTestApk = debugIncludesTests.value
    val pkg = applicationId.value
    val all = allDevices.value
    val s = streams.value
    val timeo = instrumentTestTimeout.value
    val sdk = sdkPath.value
    val runner = instrumentTestRunner.value
    val testManifest = layout.testManifest
    val manifestFile = if (noTestApk || testManifest.exists) {
      testManifest
    } else {
      generateTestManifest(pkg, layout.processedTestManifest, runner)
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
    runTests(sdk, testPackage, s, trunner, timeo, all, Some(testSelection))
  }

  private def generateTestManifest(pkg: String, manifestOut: File,
    runner: String) = {
    val TOOLS_PREFIX = "tools"
    val tns = NamespaceBinding(TOOLS_PREFIX, TOOLS_NS, TopScope)
    val vn = new PrefixedAttribute(ANDROID_PREFIX, "versionName", "1.0", Null)
    val vc = new PrefixedAttribute(ANDROID_PREFIX, "versionCode", "1", vn)
    val pkgAttr = new UnprefixedAttribute("package",
      pkg + ".instrumentTest", vc)
    val ns = NamespaceBinding(ANDROID_PREFIX, ANDROID_NS, tns)

    val minSdk = new PrefixedAttribute(
      ANDROID_PREFIX, "minSdkVersion", "3", Null)
    val usesSdk = new Elem(null, "uses-sdk", minSdk, TopScope, minimizeEmpty = true)
    val runnerlib = new PrefixedAttribute(
      ANDROID_PREFIX, "name", TEST_RUNNER_LIB, Null)
    val usesLib = new Elem(null, USES_LIBRARY_TAG, runnerlib, TopScope, minimizeEmpty = true)
    val app = new Elem(null,
      APPLICATION_TAG, Null, TopScope, minimizeEmpty = false, usesLib)
    val name = new PrefixedAttribute(
      ANDROID_PREFIX, "name", runner, Null)
    val instrumentation = new Elem(null, INSTRUMENTATION_TAG, name, TopScope, minimizeEmpty = true)
    val manifest = new Elem(null,
      "manifest", pkgAttr, ns, minimizeEmpty = false, usesSdk, app, instrumentation)

    XML.save(manifestOut.getAbsolutePath, manifest, "utf-8", true, null)
    manifestOut
  }

  private def runTests(sdk: String, testPackage: String,
      s: TaskStreams, runner: String, timeo: Int, all: Boolean, testSelection: Option[String] = None) {
    import com.android.ddmlib.testrunner.InstrumentationResultParser
    val intent = testPackage + "/" + runner
    def execute(d: IDevice): Unit = {
      val listener = TestListener(s.log)
      val p = new InstrumentationResultParser(testPackage, listener)
      val receiver = new Commands.ShellLogging(l => p.processNewLines(Array(l)))
      val selection = testSelection map { "-e " + _ } getOrElse ""
      val command = "am instrument -r -w %s %s" format(selection, intent)
      s.log.info(s"Testing on ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
      s.log.debug("Executing [%s]" format command)
      val timeout = DdmPreferences.getTimeOut
      DdmPreferences.setTimeOut(timeo)
      d.executeShellCommand(command, receiver)
      DdmPreferences.setTimeOut(timeout)

      s.log.debug("instrument command executed")

      if (listener.failures.nonEmpty) {
        Plugin.fail("Tests failed: " + listener.failures.size + "\n" +
          (listener.failures map (" - " + _)).mkString("\n"))
      }
    }
    if (all)
      Commands.deviceList(sdk, s.log).par foreach execute
    else
      Commands.targetDevice(sdk, s.log) foreach execute

  }

  val cleanTaskDef = Def.task {
    val s = streams.value
    def execute(d: IDevice): Unit = {
      val receiver = new Commands.ShellLogging(l => s.log.info(l))
      val pkg = applicationId.value
      val command = "pm clear %s" format pkg
      s.log.info(s"Clearing data for $pkg on ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
      s.log.debug("Executing [%s]" format command)
      d.executeShellCommand(command, receiver)
    }
    val all = allDevices.value
    val k = sdkPath.value
    if (all)
      Commands.deviceList(k, s.log).par foreach execute
    else
      Commands.targetDevice(k, s.log) foreach execute
  }

  val runTaskDef: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val k = sdkPath.value
    val l = projectLayout.value
    val p = applicationId.value
    val s = streams.value
    val all = allDevices.value
    val isLib = libraryProject.value
    implicit val output = outputLayout.value
    if (isLib)
      Plugin.fail("This project is not runnable, it has set 'libraryProject in Android := true")

    val r = Def.spaceDelimited().parsed
    val manifestXml = l.processedManifest
    val m = XML.loadFile(manifestXml)
    // if an arg is specified, try to launch that
    (if (r.isEmpty) None else Some(r mkString " ")) orElse ((m \\ "activity") find {
      // runs the first-found activity
      a => (a \ "intent-filter") exists { filter =>
        val attrpath = "@{%s}name" format ANDROID_NS
        (filter \\ attrpath) exists (_.text == "android.intent.action.MAIN")
      }
    } map { activity =>
      val name = activity.attribute(ANDROID_NS, "name") get 0 text

      "%s/%s" format (p, if (name.indexOf(".") == -1) "." + name else name)
    }) match {
      case Some(intent) =>
        val receiver = new Commands.ShellLogging(l => s.log.info(l))
        def execute(d: IDevice): Unit = {
          val command = "am start -n %s" format intent
          s.log.info(s"Running on ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
          s.log.debug("Executing [%s]" format command)
          d.executeShellCommand(command, receiver)
          s.log.debug("run command executed")
        }
        if (all)
          Commands.deviceList(k, s.log).par foreach execute
        else
          Commands.targetDevice(k, s.log) foreach execute
      case None =>
        Plugin.fail(
          "No activity found with action 'android.intent.action.MAIN'")
    }

    ()
  }

  val KB = 1024 * 1.0
  val MB = KB * KB
  val installTaskDef = ( packageT
                       , libraryProject
                       , sdkPath
                       , allDevices
                       , streams) map {
    (p, l, k, all, s) =>

    if (!l) {
      def execute(d: IDevice): Unit = {
        val cacheName = "install-" + URLEncoder.encode(
          d.getSerialNumber, "utf-8")
        FileFunction.cached(s.cacheDirectory / cacheName, FilesInfo.hash) { in =>
          s.log.info(s"Installing to ${d.getProperty(IDevice.PROP_DEVICE_MODEL)} (${d.getSerialNumber})...")
          installPackage(p, k, d, s.log)
          in
        }(Set(p))
      }
      if (all)
        Commands.deviceList(k, s.log).par foreach execute
      else
        Commands.targetDevice(k, s.log) foreach execute
    }
  }

  def installPackage(apk: File, sdkPath: String, device: IDevice, log: Logger) {
    val start = System.currentTimeMillis
    logRate(log, "[%s] Install finished:" format apk.getName, apk.length) {
      Option(device.installPackage(apk.getAbsolutePath, true)) map { err =>
        Plugin.fail("Install failed: " + err)
      }
    }
  }

  def logRate[A](log: Logger, msg: String, size: Long)(f: => A): A = {
    val start = System.currentTimeMillis
    val a = f
    val end = System.currentTimeMillis
    val secs = (end - start) / 1000d
    val rate = size/KB/secs
    val mrate = if (rate > MB) rate / MB else rate
    log.info("%s %s in %.2fs. %.2f%s/s" format (msg, Packaging.sizeString(size), secs, mrate,
      if (rate > MB) "MB" else "KB"))
    a
  }

  def uninstallPackage(cacheDir: Option[File], packageName: String, sdkPath: String, device: IDevice, log: Logger) {
    val cacheName = "install-" + URLEncoder.encode(
      device.getSerialNumber, "utf-8")
    cacheDir foreach { c =>
      FileFunction.cached(c / cacheName, FilesInfo.hash) { in =>
        Set.empty
      }(Set.empty)
    }
    log.info(s"Uninstalling from ${device.getProperty(IDevice.PROP_DEVICE_MODEL)} (${device.getSerialNumber})...")
    Option(device.uninstallPackage(packageName)) map { err =>
      Plugin.fail("[%s] Uninstall failed: %s" format (packageName, err))
    } getOrElse {
      log.info("[%s] Uninstall finished" format packageName)
    }
  }
  val uninstallTaskDef = (sdkPath, applicationId, streams, allDevices) map { (k,p,s,all) =>
    def uninstall(device: IDevice) {
      uninstallPackage(Some(s.cacheDirectory), p, k, device, s.log)
    }

    if (all)
      Commands.deviceList(k, s.log).par foreach uninstall
    else
      Commands.targetDevice(k, s.log) foreach uninstall
  }

  def loadLibraryReferences(b: File, props: Properties, prefix: String = ""):
  Seq[AutoLibraryProject] = {
    val p = props.asScala
    (p.keys.collect {
        case k if k.startsWith("android.library.reference") => k
      }.toList.sortWith { (a,b) => a < b } flatMap { k =>
        AutoLibraryProject(b/p(k)) +:
          loadLibraryReferences(b/p(k), loadProperties(b/p(k)), k)
      }) distinct
  }

  def loadProperties(path: File): Properties = {
    val p = new Properties
    (path * "*.properties" get) foreach { f =>
      Using.fileInputStream(f) { p.load }
    }
    p
  }

  val unmanagedJarsTaskDef = ( unmanagedJars in Compile
                             , baseDirectory
                             , buildTools in Android
                             , rsSupportMode in Android
                             , libraryProjects in Android, streams) map {
    (u, b, t, rs, l, s) =>

    val rsJars =
      if (rs) SdkLayout.renderscriptSupportLibs(t).map(f =>
        Attributed.blank(f.getCanonicalFile))
      else Seq()

    // remove scala-library if present
    // add all dependent library projects' classes.jar files
    (u ++ rsJars ++ (l filterNot {
        case _: ApkLibrary         => true
//        case _: AarLibrary         => true
        case _: AutoLibraryProject => true
        case _ => false
      } map { p => Attributed.blank(p.getJarFile.getCanonicalFile) }) ++ (for {
        d <- l/* filterNot { // currently unworking
          case _: AarLibrary => true
          case _ => false
        }*/
        j <- d.getLocalJars.asScala
      } yield Attributed.blank(j.getCanonicalFile)) ++ (for {
        d <- Seq(b / "libs", b / "lib")
        j <- d * "*.jar" get
      } yield Attributed.blank(j.getCanonicalFile))
    ) filter { c =>
      !c.data.getName.startsWith("scala-library") && c.data.isFile
    }
  }
}
