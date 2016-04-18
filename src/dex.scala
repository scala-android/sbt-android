package android

import java.io.File

import com.android.SdkConstants
import com.android.builder.core.{AndroidBuilder, DexOptions}
import com.android.sdklib.BuildToolInfo
import sbt._

import scala.language.postfixOps

object Dex {
  import ProguardUtil._
  def dexInputs(progOut: Option[File], in: ProguardInputs,
      pa: Aggregate.Proguard, ra: Aggregate.Retrolambda,
      multiDex: Boolean, b: File, deps: sbt.Keys.Classpath,
      classJar: File, debug: Boolean, s: sbt.Keys.TaskStreams) = {
    val re = ra.enable
    val bldr = ra.builder
    val progCache = pa.proguardCache
    val proguardRelease = pa.useProguard
    val proguardDebug = pa.useProguardInDebug

    val proguarding = (proguardDebug && debug) || (proguardRelease && !debug)
    // TODO use getIncremental in DexOptions instead
    val proguardedDexMarker = b / ".proguarded-dex"
    // disable incremental dex on first proguardcache-hit run
    val incrementalDex = debug && (progCache.isEmpty || !proguardedDexMarker.exists)

    val jarsToDex = progOut map { obfuscatedJar =>
      IO.touch(proguardedDexMarker, setModified = false)
      Seq(obfuscatedJar)
    } getOrElse {
      proguardedDexMarker.delete()
      // TODO cache the jar file listing
      def dexingDeps = deps filter (_.data.isFile) filterNot (file =>
        progCache.nonEmpty && proguarding && (listjar(file) exists (inPackages(_, progCache))))
      val inputs = dexingDeps.collect {
        case x if x.data.getName.startsWith("scala-library") && (!proguarding || multiDex) =>
          x.data.getCanonicalFile
        case x if x.data.getName.endsWith(".jar") =>
          x.data.getCanonicalFile
      } ++ in.proguardCache :+ classJar
      // TODO may fail badly in the presence of proguard-cache?
      if (re && RetrolambdaSupport.isAvailable)
        RetrolambdaSupport(b, inputs, ra.classpath, ra.bootClasspath, s)
      else inputs
    }

    // also disable incremental on proguard run
    (incrementalDex && !proguardedDexMarker.exists) -> jarsToDex
  }
  def dex(bldr: AndroidBuilder, dexOpts: Aggregate.Dex, pd: Seq[(File,File)],
      pg: Option[File], legacy: Boolean, lib: Boolean,
      bin: File, shard: Boolean, debug: Boolean, s: sbt.Keys.TaskStreams) = {
    val xmx = dexOpts.maxHeap
    val (incr, inputs) = dexOpts.inputs
    val multiDex = dexOpts.multi
    val mainDexListTxt = dexOpts.mainClassesConfig
    val minMainDex = dexOpts.minimizeMain
    val maxProc = dexOpts.maxProcessCount
    val additionalParams = dexOpts.additionalParams
    val incremental = incr && !multiDex
    //    if (dexes.isEmpty || dexIn.exists(i => dexes exists(_.lastModified <= i.lastModified))) {

    if (!legacy && shard && debug) {
      shardedDex(bldr, inputs, pd, incremental, xmx, maxProc, additionalParams,
        bin, debug, s)
    } else {
      singleDex(bldr, inputs, pd, incremental, legacy, multiDex, minMainDex,
        mainDexListTxt, xmx, maxProc, additionalParams, bin, debug, s)
    }
  }

  private[this] def shardedDex(bldr: AndroidBuilder,
      inputs: Seq[File], pd: Seq[(File,File)],
      incremental: Boolean,
      xmx: String, maxProc: Int,
      additionalParams: Seq[String], bin: File,
      debug: Boolean, s: sbt.Keys.TaskStreams) = {
    import collection.JavaConverters._
    val dexIn = (inputs filter (_.isFile)) filterNot (pd map (_._1) contains _)
    // double actual number, because "dex methods" include references to other methods
    lazy val totalMethods = (dexIn map MethodCounter.apply).sum * 2
    // try to aim for an average of 3000 methods per shard
    val SHARD_GOAL = 3000
    val MAX_SHARDS = 50
    val SUFFIX_LEN = ".class".length
    lazy val shards = math.min(math.max(1, totalMethods / SHARD_GOAL), math.max(10, MAX_SHARDS - pd.size))
    val shardClasses = bin / "shard-classes"
    val dexInUnpacked = bin / "shard-jars"
    dexInUnpacked.mkdirs()
    val unpackedClasses = dexIn flatMap { in =>
      val loc = dexInUnpacked / predexFileName(in)
      val outs = FileFunction.cached(s.cacheDirectory / s"unpack-${loc.getName}", FilesInfo.hash) { jar =>
        s.log.debug(s"Unpacking ${jar.head}")
        IO.unzip(jar.head, loc)
      }(Set(in))
      outs filter (_.getName.endsWith(".class")) map ((loc,_))
    } map { case ((loc,f)) =>
      val name = f.relativeTo(loc).fold(Plugin.fail(s"$f is not relative to $loc"))(_.getPath)
      // shard by top-level classname hashcode
      val i = name.indexOf("$")
      val shardTarget = 1 + math.abs((if (i != -1) name.substring(0, i) else name.dropRight(SUFFIX_LEN)).hashCode % shards)
      (f, shardClasses / f"$shardTarget%02d" / name)
    }

    IO.copy(unpackedClasses)

    val dexShards = shardClasses * "*" get

    s.log.debug("Shard classes to process " + dexShards)

    (bin * "*.dex" get) foreach (_.delete())

    val shardDex = bin / "shard-dex"
    (dexShards flatMap { shard =>
      val sn = shard.getName
      val shardPath = shardDex / sn
      shardPath.mkdirs()
      FileFunction.cached(s.cacheDirectory / s"dex-$sn", FilesInfo.hash) { in =>
        val options = new DexOptions {
          override def getIncremental = incremental
          override def getJavaMaxHeapSize = xmx
          override def getPreDexLibraries = false
          override def getJumboMode = false
          override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
          override def getMaxProcessCount = maxProc
        }
        s.log.debug(s"$sn: Dex inputs: " + shard)

        val tmp = s.cacheDirectory / s"dex-$sn"
        tmp.mkdirs()

        val predex2 = pd flatMap (_._2 * "*.dex" get)
        s.log.debug("PRE-DEXED: " + predex2)
        bin.mkdirs()
        // dex doesn't support --no-optimize, see
        // https://android.googlesource.com/platform/tools/base/+/9f5a5e1d91a489831f1d3cc9e1edb850514dee63/build-system/gradle-core/src/main/groovy/com/android/build/gradle/tasks/Dex.groovy#219
        bldr.convertByteCode(Seq(shard).asJava, shardPath,
          false, null, options, additionalParams.asJava, incremental, true, SbtProcessOutputHandler(s.log), false)
        val result = shardPath * "*.dex" get

        s.log.info(s"$sn: Generated dex shard, method count: " + (result map (dexMethodCount(_, s.log))).sum)
        result.toSet
      }((shard ** "*.class" get).toSet)
    } toList).sorted.zipWithIndex.foreach { case (f,i) =>
      // dex file names must be classes.dex, classesN0.dex, classN0+i.dex where N0=2
      IO.copyFile(f, bin / s"classes${if (i == 0) "" else (i+1).toString}.dex")
    }

    bin
  }
  private[this] def singleDex(bldr: AndroidBuilder,
      inputs: Seq[File], pd: Seq[(File,File)],
      incremental: Boolean,
      legacy: Boolean,
      multiDex: Boolean, minMainDex: Boolean,
      mainDexListTxt: File, xmx: String, maxProc: Int,
      additionalParams: Seq[String], bin: File,
      debug: Boolean, s: sbt.Keys.TaskStreams) = {
    import collection.JavaConverters._
    val dexIn = (inputs filter (_.isFile)) filterNot (pd map (_._1) contains _)
    val dexes = (bin ** "*.dex").get
    FileFunction.cached(s.cacheDirectory / "dex", FilesInfo.lastModified) { in =>
      s.log.debug("Invalidated cache because: " + dexIn.filter(i => dexes.exists(_.lastModified <= i.lastModified)))
      if (!incremental && inputs.exists(_.getName.startsWith("proguard-cache"))) {
        s.log.debug("Cleaning dex files for proguard cache and incremental dex")
        (bin * "*.dex" get) foreach (_.delete())
      }
      val options = new DexOptions {
        override def getIncremental = incremental
        override def getJavaMaxHeapSize = xmx
        override def getPreDexLibraries = false
        override def getJumboMode = false
        override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
        override def getMaxProcessCount = maxProc
      }
      s.log.info(s"Generating dex, incremental=$incremental, multidex=$multiDex")
      s.log.debug("Dex inputs: " + inputs)

      val tmp = s.cacheDirectory / "dex"
      tmp.mkdirs()

      def minimalMainDexParam = if (minMainDex) "--minimal-main-dex" else ""
      val additionalDexParams = (additionalParams.toList :+ minimalMainDexParam).distinct.filterNot(_.isEmpty)

      val predex2 = pd flatMap (_._2 * "*.dex" get)
      s.log.debug("DEX IN: " + dexIn)
      s.log.debug("PRE-DEXED: " + predex2)
      bin.mkdirs()
      // dex doesn't support --no-optimize, see
      // https://android.googlesource.com/platform/tools/base/+/9f5a5e1d91a489831f1d3cc9e1edb850514dee63/build-system/gradle-core/src/main/groovy/com/android/build/gradle/tasks/Dex.groovy#219
      bldr.convertByteCode(dexIn.asJava, bin,
        multiDex, if (!legacy) null else mainDexListTxt,
        options, additionalDexParams.asJava, incremental, true, SbtProcessOutputHandler(s.log), false)
      s.log.info("dex method count: " + ((bin * "*.dex" get) map (dexMethodCount(_, s.log))).sum)
      (bin ** "*.dex").get.toSet
    }(dexIn.toSet)

    bin
  }

  def predexFileName(inFile: File) = {
    val n = inFile.getName
    val pos = n.lastIndexOf('.')

    val name = if (pos != -1) n.substring(0, pos) else n

    // add a hash of the original file path.
    val input = inFile.getAbsolutePath
    val hashCode = Hash.toHex(Hash(input))

    name + "-" + hashCode.toString + SdkConstants.DOT_JAR
  }
  def predexFileOutput(base: File, binPath: File, inFile: File) = {
    val rpath = inFile relativeTo base
    val f = rpath.fold(SdkLayout.predex)(_ => binPath) / predexFileName(inFile)
    f.mkdirs()
    f
  }

  def predex(opts: Aggregate.Dex, inputs: Seq[File], multiDex: Boolean,
      legacy: Boolean, classes: File, pg: Option[File],
      bldr: AndroidBuilder, base: File, bin: File,
      s: sbt.Keys.TaskStreams) = {
    bin.mkdirs()
    val options = new DexOptions {
      override def getIncremental = false
      override def getJavaMaxHeapSize = opts.maxHeap
      override def getPreDexLibraries = false
      override def getJumboMode = false
      override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
      override def getMaxProcessCount = opts.maxProcessCount
    }
    if (!legacy && multiDex) {
      ((inputs filterNot (i => i == classes || pg.exists(_ == i))) map { i =>
        val out = predexFileOutput(base, bin, i)
        val predexed = out * "*.dex" get

        if (predexed.isEmpty || predexed.exists (_.lastModified < i.lastModified)) {
          predexed foreach (_.delete())
          s.log.debug("Pre-dex input: " + i.getAbsolutePath)
          s.log.info("Pre-dexing: " + i.getName)
          bldr.preDexLibraryNoCache(i, out, multiDex, options, SbtProcessOutputHandler(s.log))
        }
        (i,out)
      }).toList: Seq[(File,File)]
    } else Nil
  }

  def dexMainClassesConfig(layout: ProjectLayout, legacy: Boolean, multidex: Boolean,
      inputs: Seq[File], mainDexClasses: Seq[String],
      bt: BuildToolInfo, s: sbt.Keys.TaskStreams)(implicit m: BuildOutput.Converter) = {
    val mainDexListTxt = layout.maindexlistTxt.getAbsoluteFile
    if (multidex && legacy) {
      if (mainDexClasses.nonEmpty) {
        IO.writeLines(mainDexListTxt, mainDexClasses)
      } else {
        val btl = bt.getLocation
        val script = if (!Commands.isWindows) btl / "mainDexClasses"
        else {
          val f = btl / "mainDexClasses.cmd"
          if (f.exists) f else btl / "mainDexClasses.bat"
        }
        val injars = inputs map (_.getAbsolutePath) mkString File.pathSeparator
        FileFunction.cached(s.cacheDirectory / "mainDexClasses", FilesInfo.lastModified) { in =>
          val cmd = Seq(
            script.getAbsolutePath,
            "--output", mainDexListTxt.getAbsolutePath,
            if (Commands.isWindows) s""""$injars"""" else injars
          )

          s.log.info("Generating maindexlist.txt")
          s.log.debug("mainDexClasses => " + cmd.mkString(" "))
          val rc = Process(cmd, layout.base) !

          if (rc != 0) {
            Plugin.fail("failed to determine mainDexClasses")
          }
          s.log.warn("Set mainDexClasses to improve build times:")
          s.log.warn("""  dexMainClassesConfig := baseDirectory.value / "copy-of-maindexlist.txt"""")
          Set(mainDexListTxt)
        }(inputs.toSet)

      }
    } else
      mainDexListTxt.delete()
    mainDexListTxt
  }

  // see https://source.android.com/devices/tech/dalvik/dex-format.html
  def dexMethodCount(dexFile: File, log: Logger): Int = {
    import java.nio.{ByteBuffer,ByteOrder}
    val header_size = 0x70
    val endian_constant = 0x12345678
    val reverse_endian_constant = 0x78563412
    val dex_magic = Array(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00) map (_.toByte)
    val buf = Array.ofDim[Byte](header_size)
    Using.fileInputStream(dexFile) { fin =>
      fin.read(buf)
      val header = ByteBuffer.wrap(buf)
      val isDex = (dex_magic zip buf) forall { case (a, b) => a == b }
      if (isDex) {
        header.order(ByteOrder.LITTLE_ENDIAN)
        header.position(40) // endian_tag
        val endianness = header.getInt
        val isBE = endianness == reverse_endian_constant
        if (isBE)
          header.order(ByteOrder.BIG_ENDIAN)
        else if (endianness != endian_constant) {
          log.warn(dexFile.getName + " does not define endianness properly")
        }

        header.position(88) // method_ids_size
        val methodIds = header.getInt
        methodIds
      } else {
        log.info(dexFile.getName + " is not a valid dex file")
        0
      }
    }
  }
}

