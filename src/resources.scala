package android

import java.io.File

import android.Dependencies.{AarLibrary, ApkLibrary, LibraryDependency}
import com.android.builder.core.{AaptPackageProcessBuilder, AndroidBuilder, VariantType}
import com.android.builder.model.AaptOptions
import com.android.builder.dependency.{LibraryDependency => AndroidLibrary}
import com.android.builder.png.VectorDrawableRenderer
import com.android.ide.common.res2._
import com.android.resources.Density
import com.android.utils.ILogger
import sbt.Keys.TaskStreams
import sbt._

import collection.JavaConverters._
import language.postfixOps
import Dependencies.LibrarySeqOps
import sbt.classpath.ClasspathUtilities

import scala.util.Try
import scala.xml.XML

object Resources {
  val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  def resourceUrl =
    Resources.getClass.getClassLoader.getResource _

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

  def doCollectResources( bldr: AndroidBuilder
                          , minSdk: Int
                          , noTestApk: Boolean
                          , isLib: Boolean
                          , libs: Seq[LibraryDependency]
                          , layout: ProjectLayout
                          , extraAssets: Seq[File]
                          , extraRes: Seq[File]
                          , renderVectors: Boolean
                          , logger: ILogger
                          , cache: File
                          , s: TaskStreams
                          )(implicit m: BuildOutput.Converter): (File,File) = {
    val assetBin = layout.mergedAssets
    val assets = layout.assets
    val resTarget = layout.mergedRes
    val rsResources = layout.rsRes

    resTarget.mkdirs()
    assetBin.mkdirs

    val depassets = collectdeps(libs) collect {
      case m: ApkLibrary => m
      case n: AarLibrary => n
    } collect { case n if n.getAssetsFolder.isDirectory => n.getAssetsFolder }
    // copy assets to single location
    depassets ++ (libs collect {
      case r if r.layout.assets.isDirectory => r.layout.assets
    }) foreach { a => IO.copyDirectory(a, assetBin, false, true) }
    extraAssets foreach { a =>
      if (a.isDirectory) IO.copyDirectory(a, assetBin, false, true)
    }

    if (assets.exists) IO.copyDirectory(assets, assetBin, false, true)
    if (noTestApk && layout.testAssets.exists)
      IO.copyDirectory(layout.testAssets, assetBin, false, true)
    // prepare resource sets for merge
    val res = extraRes ++ Seq(layout.res, rsResources) ++
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
    val vectorprocessor = new VectorDrawableRenderer(
      if (renderVectors) minSdk else math.max(minSdk,21),
      layout.generatedVectors, Set(Density.MEDIUM,
        Density.HIGH,
        Density.XHIGH,
        Density.XXHIGH).asJava,
      logger)
    val sets = respaths.distinct flatMap { r =>
      val set = new ResourceSet(r.getAbsolutePath)
      set.addSource(r)

      set.setPreprocessor(vectorprocessor)
      val generated = new GeneratedResourceSet(set)
      set.setGeneratedSet(generated)

      s.log.debug("Adding resource path: " + r)
      List(generated, set)
    }

    val inputs = (respaths flatMap { r => (r ***) get }) filter (n =>
      !n.getName.startsWith(".") && !n.getName.startsWith("_"))
    var needsFullResourceMerge = false

    FileFunction.cached(cache / "nuke-res-if-changed", FilesInfo.lastModified) { in =>
      needsFullResourceMerge = true
      IO.delete(resTarget)
      in
    }(depres.toSet)
    FileFunction.cached(cache / "collect-resources")(
      FilesInfo.lastModified, FilesInfo.exists) { (inChanges,outChanges) =>
      s.log.info("Collecting resources")

      incrResourceMerge(layout, minSdk, resTarget, isLib, libs, cache / "collect-resources",
                        logger, bldr, sets, vectorprocessor, inChanges, needsFullResourceMerge, s.log)
      ((resTarget ** FileOnlyFilter).get ++ (layout.generatedVectors ** FileOnlyFilter).get).toSet
    }(inputs.toSet)

    (assetBin, resTarget)
  }

  def incrResourceMerge(
    layout: ProjectLayout,
    minSdk: Int,
    resTarget: File,
    isLib: Boolean,
    libs: Seq[LibraryDependency],
    blobDir: File,
    logger: ILogger,
    bldr: AndroidBuilder,
    resources: Seq[ResourceSet],
    preprocessor: ResourcePreprocessor,
    changes: ChangeReport[File],
    needsFullResourceMerge: Boolean,
    slog: Logger
  )(implicit m: BuildOutput.Converter) {

    def merge() = fullResourceMerge(layout, minSdk, resTarget, isLib, libs, blobDir,
                                    logger, bldr, resources, preprocessor, slog)

    val merger = new ResourceMerger(minSdk)
    if (!merger.loadFromBlob(blobDir, true)) {
      slog.debug("Could not load merge blob (no full merge yet?)")
      merge()
    } else if (!merger.checkValidUpdate(resources.asJava)) {
      slog.debug("requesting full merge: !checkValidUpdate")
      merge()
    } else if (needsFullResourceMerge) {
      slog.debug("requesting full merge: dependency resources have changed!")
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
        val writer = new MergedResourceWriter(resTarget,
          bldr.getAaptCruncher(SbtProcessOutputHandler(slog)),
          true, true, layout.publicTxt, layout.mergeBlame,
          preprocessor)
        merger.mergeData(writer, true)
        merger.writeBlobTo(blobDir, writer)
      }
    }
  }
  def fullResourceMerge(layout: ProjectLayout, minSdk: Int, resTarget: File, isLib: Boolean,
                        libs: Seq[LibraryDependency], blobDir: File, logger: ILogger,
                        bldr: AndroidBuilder, resources: Seq[ResourceSet],
                        preprocessor: ResourcePreprocessor, slog: Logger)(implicit m: BuildOutput.Converter) {

    slog.info("Performing full resource merge")
    val merger = new ResourceMerger(minSdk)

    resTarget.mkdirs()

    resources foreach { r =>
      r.loadFromFiles(logger)
      merger.addDataSet(r)
    }
    val writer = new MergedResourceWriter(resTarget,
      bldr.getAaptCruncher(SbtProcessOutputHandler(slog)),
      true, true, layout.publicTxt, layout.mergeBlame, preprocessor)
    merger.mergeData(writer, false)
    merger.writeBlobTo(blobDir, writer)
  }

  def aapt(bldr: AndroidBuilder, manifest: File, pkg: String,
           extraParams: Seq[String],
           libs: Seq[LibraryDependency], lib: Boolean, debug: Boolean,
           res: File, assets: File, resApk: String, gen: File, proguardTxt: String,
           logger: Logger) = synchronized {

    gen.mkdirs()
    val options = new AaptOptions {
      override def getIgnoreAssets = null
      override def getNoCompress = null
      override def getFailOnMissingConfigEntry = false
      override def getAdditionalParameters = extraParams.asJava
    }
    val genPath = gen.getAbsolutePath
    val all = collectdeps(libs)
    logger.debug("All libs: " + all)
    logger.debug("packageForR: " + pkg)
    logger.debug("proguard.txt: " + proguardTxt)
    val aaptCommand = new AaptPackageProcessBuilder(manifest, options)
    if (res.isDirectory)
      aaptCommand.setResFolder(res)
    if (assets.isDirectory)
      aaptCommand.setAssetsFolder(assets)
    aaptCommand.setLibraries(all.asJava)
    aaptCommand.setPackageForR(pkg)
    aaptCommand.setResPackageOutput(resApk)
    aaptCommand.setSourceOutputDir(if (resApk == null) genPath else null)
    aaptCommand.setSymbolOutputDir(if (resApk == null) genPath else null)
    aaptCommand.setProguardOutput(proguardTxt)
    aaptCommand.setType(if (lib) VariantType.LIBRARY else VariantType.DEFAULT)
    aaptCommand.setDebuggable(debug)
    try {
      bldr.processResources(aaptCommand, true, SbtProcessOutputHandler(logger))
    } catch {
      case e: com.android.ide.common.process.ProcessException =>
        PluginFail(e.getMessage)
    }
  }

  def collectdeps(libs: Seq[AndroidLibrary]): Seq[AndroidLibrary] = {
    libs
      .map(_.getDependencies.asScala)
      .flatMap(collectdeps)
      .++(libs)
      .distinctLibs
  }

  def generateTR(t: Boolean, a: Seq[File], p: String, layout: ProjectLayout,
                 platformApi: Int, platform: (String,Seq[String]), sv: String,
                 l: Seq[LibraryDependency], i: Seq[String], s: TaskStreams): Seq[File] = {

    val j = platform._1
    val r = layout.res
    val g = layout.gen
    val ignores = i.toSet

    val tr = p.split("\\.").foldLeft (g) { _ / _ } / "TR.scala"

    if (!t)
      Seq.empty[File]
    else
      FileFunction.cached(s.cacheDirectory / "typed-resources-generator", FilesInfo.hash) { in =>
        if (in.nonEmpty) {
          s.log.info("Regenerating TR.scala because R.java has changed")
          val androidjar = ClasspathUtilities.toLoader(file(j))
          val layouts = (r ** "layout*" ** "*.xml" get) ++
            (for {
              lib <- l filterNot {
                case p: Dependencies.Pkg => ignores(p.pkg)
                case _                   => false
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

          val resdirs = r +:
            (for {
              lib <- l filterNot {
                case p: Dependencies.Pkg => ignores(p.pkg)
                case _                   => false
              }
            } yield lib.getResFolder)
          val rms1 = processValuesXml(resdirs, s)
          val rms2 = processResourceTypeDirs(resdirs, s)
          val combined = reduceResourceMap(Seq(rms1, rms2)).filter(_._2.nonEmpty)
          val combined1 = combined.map { case (k, xs) =>
            val k2 = if (k endsWith "-array") "array" else k
            val trt = trTypes(k)
            val ys = xs.toSet[String].map { x =>
              val y = x.replace('.', '_')
              s"    final val ${wrap(y)} = TypedRes[TypedResource.$trt](R.$k2.${wrap(y)})"
            }
            k -> ys
          }
          val combined2 = combined1.foldLeft(emptyResourceMap) { case (acc, (k, xs)) =>
            val k2 = if (k endsWith "-array") "array" else k
            acc + ((k2, acc(k2) ++ xs))
          }
          val trs = combined2.foldLeft(List.empty[String]) { case (acc, (k, xs)) =>
            val k2 = if (k endsWith "-array") "array" else k
            s"""
               |  object $k2 {
               |${xs.mkString("\n")}
               |  }""".stripMargin :: acc
          }

          val getColor = if (platformApi >= 23) {
            s"""      if (Build.VERSION.SDK_INT >= 23)
               |        c.getColor(resid)
               |      else
               |        c.getResources.getColor(resid)""".stripMargin
          } else {
            "c.getResources.getColor(resid)"
          }
          val getDrawable = if (platformApi >= 21) {
            s"""      if (Build.VERSION.SDK_INT >= 21)
               |        c.getDrawable(resid)
               |      else
               |        c.getResources.getDrawable(resid)
             """.stripMargin
          } else {
            "c.getResources.getDrawable(resid)"
          }

          IO.write(tr, trTemplate format (p,
            resources map { case (k,v) =>
              "  final val %s = TypedResource[%s](R.id.%s)" format (wrap(k),v,wrap(k))
            } mkString "\n",
            layoutTypes map { case (k,v) =>
              "    final val %s = TypedLayout[%s](R.layout.%s)" format (wrap(k),v,wrap(k))
            } mkString "\n", trs.mkString, getColor, getDrawable, getDrawable))
          Set(tr)
        } else Set.empty
      }(a.toSet).toSeq
  }
  def wrap(s: String) = if (reservedWords(s)) s"`$s`" else s

  val trTypes = Map(
    "anim"          -> "ResAnim",
    "animator"      -> "ResAnimator",
    "array"         -> "ResArray",
    "string-array"  -> "ResStringArray",
    "integer-array" -> "ResIntegerArray",
    "attr"          -> "ResAttr",
    "bool"          -> "ResBool",
    "color"         -> "ResColor",
    "dimen"         -> "ResDimen",
    "drawable"      -> "ResDrawable",
    "fraction"      -> "ResFraction",
    "integer"       -> "ResInteger",
    "interpolator"  -> "ResInterpolator",
    "menu"          -> "ResMenu",
    "mipmap"        -> "ResMipMap",
    "plurals"       -> "ResPlurals",
    "raw"           -> "ResRaw",
    "string"        -> "ResString",
    "style"         -> "ResStyle",
    "transition"    -> "ResTransition",
    "xml"           -> "ResXml"
  )

  val itemTypes = Set(
    "anim",
    "animator",
    "array",
    "bool",
    "color",
    "dimen",
    "drawable",
    "fraction",
    "integer",
    "interpolator",
    "menu",
    "mipmap",
    "plurals",
    "raw",
    "string",
    "style",
    "transition",
    "xml"
  )

  val formatTypes = List(
    "boolean"   -> "bool",
    "color"     -> "color",
    "dimension" -> "dimen",
    "fraction"  -> "fraction",
    "integer"   -> "integer",
    "string"    -> "string"
  ).toMap

  type ResourceMap = Map[String,List[String]]
  val emptyResourceMap = Map.empty[String,List[String]].withDefaultValue(Nil)
  def reduceResourceMap(rms: Seq[ResourceMap]): ResourceMap =
    rms.foldLeft(emptyResourceMap) { (m, n) =>
      n.keys.foldLeft(m)((m2, k) => m2 + (k -> (m2(k) ++ n(k))))
    }
  def attributeText(n: xml.Node, attr: String): Option[String] =
    n.attribute(attr).flatMap(_.headOption).map(_.text)
  def processValuesXml(resdirs: Seq[File], s: TaskStreams): ResourceMap = {
    val valuesxmls = resdirs flatMap { d => d * "values*" * "*.xml" get }
    val rms = valuesxmls.map { xml =>
      val values = XML.loadFile(xml)

      val items = values \ "item"
      val itemEntries = items.flatMap { node =>
        (for {
          name <- attributeText(node, "name")
          typ <- attributeText(node, "type").filter(itemTypes).orElse(
            attributeText(node, "format").flatMap(formatTypes.get))
        } yield (typ, name)).toSeq
      }
      val itemMap = itemEntries.foldLeft(emptyResourceMap) { case (m, (t,n)) =>
        m + ((t,n :: m(t)))
      }

      def foldKey(key: String): (ResourceMap,scala.xml.Node) => ResourceMap = (m,node) => {
        node.attribute("name").flatMap(_.headOption).fold(m)(n => m + ((key,n.text :: m(key))))
      }
      def foldNodes(in: ResourceMap, key: String): ResourceMap = {
        (values \ key).foldLeft(in)(foldKey(key))
      }

      List("string", "string-array", "array", "plurals", "integer",
        "integer-array", "bool", "attr", "color", "dimen", "style"
      ).foldLeft(itemMap)(foldNodes)
    }
    reduceResourceMap(rms)
  }
  val resdirTypes = List(
    "anim",
    "animator",
    "color",
    "drawable",
    "interpolator",
    "menu",
    "mipmap",
    "raw",
    "transition",
    "xml"
  )

  def processResourceTypeDirs(resdirs: Seq[File], s: TaskStreams): ResourceMap = {
    val rms2 = for {
      res <- resdirs
      restype <- resdirTypes
    } yield restype ->
      (res * s"$restype*" * "*").get.map(_.getName.takeWhile(_ != '.')).toList.filter(_.nonEmpty)
    rms2.foldLeft(emptyResourceMap) { case (m, (t, xs)) => m + (t -> (m(t) ++ xs)) }
  }
}
