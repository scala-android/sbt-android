package android

import java.io.{File, InputStream, OutputStream}
import java.util.concurrent.{LinkedBlockingDeque, ThreadPoolExecutor, TimeUnit}
import java.util.jar.JarFile
import java.util.zip.{Deflater, ZipEntry}

import android.Keys.PackagingOptions
import com.android.SdkConstants
import com.android.apkzlib.zfile.{ApkCreatorFactory, ApkZFileCreatorFactory}
import com.android.apkzlib.zip.ZFileOptions
import com.android.apkzlib.zip.compress.{BestAndDefaultDeflateExecutorCompressor, DeflateExecutionCompressor}
import com.android.builder.core.AndroidBuilder
import com.android.builder.files.{IncrementalRelativeFileSets, RelativeFile}
import com.android.builder.internal.packaging.IncrementalPackager
import com.android.builder.model.AaptOptions
import com.android.builder.packaging.{DuplicateFileException, PackagingUtils}
import com.android.ide.common.res2.FileStatus
import com.android.ide.common.signing.KeystoreHelper
import com.android.utils.ILogger
import com.google.common.collect.ImmutableMap
import sbt.Def.Classpath
import sbt.Keys.moduleID
import sbt._

import scala.collection.JavaConverters._

/**
 * @author pfnguyen
 */
object Packaging {
  case class Jars( managed: Classpath
                 , unmanaged: Classpath
                 , depenendices: Classpath) {

    def isScalaLang(module: ModuleID) = module.organization == "org.scala-lang"
    val providedConfigurations = Set("provided", "compile-internal", "plugin->default(compile)")
    def isProvidedDependency(module: ModuleID) = module.configurations exists providedConfigurations

    // filtering out org.scala-lang should not cause an issue
    // they should not be changing on us anyway
    lazy val list = (managed ++ unmanaged ++ depenendices).filter {
      a => (a.get(moduleID.key) forall { moduleId =>
        !isScalaLang(moduleId) && !isProvidedDependency(moduleId)
      }) && a.data.exists
    }.groupBy(_.data.getName).collect {
      case ("classes.jar", xs) => xs.distinct
      case (_,xs) if xs.head.data.isFile => xs.head :: Nil
    }.flatten.map (_.data).toList
  }

  @deprecated("Use apkbuild(bldr: AndroidBuilder, jars: Packaging.Jars, ...) instead", "1.6.1")
  def apkbuild( bldr: AndroidBuilder
              , managed: Classpath
              , unmanaged: Classpath
              , dependenciesCp: Classpath
              , isLib: Boolean
              , aggregateOptions: Aggregate.Apkbuild
              , abiFilter: Set[String]
              , collectJniOut: File
              , resFolder: File
              , collectResourceFolder: File
              , collectAssetsFolder: File
              , output: File
              , logger: ILogger
              , s: sbt.Keys.TaskStreams): File = {
    apkbuild(
      bldr, Jars(managed, unmanaged, dependenciesCp), isLib,
      aggregateOptions, abiFilter, collectJniOut, resFolder,
      collectResourceFolder, collectAssetsFolder, output, logger, s
    )
  }

  def apkbuild( bldr: AndroidBuilder
              , dependencyJars: Jars
              , isLib: Boolean
              , aggregateOptions: Aggregate.Apkbuild
              , abiFilter: Set[String]
              , collectJniOut: File
              , resFolder: File
              , collectResourceFolder: File
              , collectAssetsFolder: File
              , output: File
              , logger: ILogger
              , s: sbt.Keys.TaskStreams): File = {

    val options = aggregateOptions.packagingOptions
    val shrinker = aggregateOptions.resourceShrinker
    val dexFolder = aggregateOptions.dex
    val predex = aggregateOptions.predex
    val jniFolders = aggregateOptions.collectJni
    val debug = aggregateOptions.apkbuildDebug
    val debugSigningConfig = aggregateOptions.debugSigningConfig
    val minSdk = aggregateOptions.minSdkVersion
    import language.postfixOps
    if (isLib)
      PluginFail("This project cannot build APK, it has set 'libraryProject := true'")
    val predexed = predex flatMap (_._2 * "*.dex" get) map (_.getParentFile)

    val jars = dependencyJars.list
    s.log.debug("jars to process for resources: " + jars)

    val jarAbiPattern = "lib/([^/]+)/[^/]+".r
    val folderAbiPattern = "([^/]+)/[^/]+".r
    val filenamePattern = ".*\\.so".r

    // filter out libraries that include a signature for no good reason
    // here's looking at you crashlytics
    val nonSignaturePattern = "META-INF/[^/]*\\.(RSA|SF)".r.pattern
    // consider rejecting other files under META-INF?
    val isResourceFile: String => Boolean = s =>
      !s.endsWith(SdkConstants.DOT_CLASS) &&
        !s.endsWith(SdkConstants.DOT_NATIVE_LIBS) &&
        !nonSignaturePattern.matcher(s).matches

    FileFunction.cached(s.cacheDirectory / "apkbuild-collect-resources", FilesInfo.lastModified) { _ =>
      collectNonAndroidResources(resFolder :: jars, collectResourceFolder, options, s.log,
        ResourceCollector(isResourceFile, isResourceFile, identity, identity))
      (collectResourceFolder ** FileOnlyFilter).get.toSet
    }(((resFolder ** FileOnlyFilter).get ++ jars).toSet)
    FileFunction.cached(s.cacheDirectory / "apkbuild-collect-jni", FilesInfo.lastModified) { _ =>
      collectNonAndroidResources(jniFolders ++ jars, collectJniOut, options, s.log, ResourceCollector(
        s => {
          val m = jarAbiPattern.pattern.matcher(s)
          m.matches && {
            val filename = s.substring(5 + m.group(1).length)
            filenamePattern.pattern.matcher(filename).matches ||
              filename == SdkConstants.FN_GDBSERVER ||
              filename == SdkConstants.FN_GDB_SETUP
          }
        }, s => {
          val m = folderAbiPattern.pattern.matcher(s)
          m.matches && {
            val filename = s.substring(1 + m.group(1).length)
            filenamePattern.pattern.matcher(filename).matches ||
              filename == SdkConstants.FN_GDBSERVER ||
              filename == SdkConstants.FN_GDB_SETUP
          }
        },
        s => SdkConstants.FD_APK_NATIVE_LIBS + "/" + s,
        s => s.substring(SdkConstants.FD_APK_NATIVE_LIBS.length + 1)
      ))
      (collectJniOut ** FileOnlyFilter).get.toSet
    }((jniFolders.flatMap(_ ** FileOnlyFilter get) ++ jars).toSet)
    packageApk(output, bldr, minSdk, aggregateOptions.manifest,
      shrinker, debugSigningConfig, collectAssetsFolder, (dexFolder +: predexed).toList,
      collectJniOut, collectResourceFolder, abiFilter, debug, s)
    s.log.debug("Including predexed: " + predexed)
    s.log.info("Packaged: %s (%s)" format (
      output.getName, sizeString(output.length)))
    output
  }

  def deltas(cacheName: String, files: List[File], base: File => File, s: sbt.Keys.TaskStreams): ImmutableMap[RelativeFile,FileStatus] = {
    var result = Option.empty[ImmutableMap[RelativeFile,FileStatus]]
    s.log.debug("Checking for changes in " + files.mkString(","))

    FileFunction.cached(s.cacheDirectory / cacheName)(FilesInfo.lastModified, FilesInfo.exists) { (ins, _) =>
      s.log.debug("Found changes!")
      val ds = if (ins.added == ins.checked) ins.added.toList.map { f =>
        new RelativeFile(base(f), f) -> FileStatus.NEW
      } else {
        ins.added.toList.map { f =>
          new RelativeFile(base(f), f) -> FileStatus.NEW
        } ++ ins.removed.toList.map { f =>
          new RelativeFile(base(f), f) -> FileStatus.REMOVED
        } ++ ins.modified.toList.map { f =>
          new RelativeFile(base(f), f) -> FileStatus.CHANGED
        }
      }

      result = Some(ImmutableMap.copyOf(ds.toMap.asJava))

      ins.checked
    }(files.toSet)

    result.getOrElse(ImmutableMap.of())
  }

  def packageApk(apk: File,
                 bldr: AndroidBuilder,
                 minSdkVersion: Int,
                 manifest: File,
                 resapk: File,
                 signingConfig: ApkSigningConfig,
                 assetFolder: File,
                 dexFolders: List[File],
                 jniFolder: File,
                 javaResFolder: File,
                 abiFilter: Set[String],
                 debug: Boolean,
                 s: sbt.Keys.TaskStreams): Unit = {
    val certInfo = KeystoreHelper.getCertificateInfo(signingConfig.storeType,
      signingConfig.keystore,
      signingConfig.storePass,
      signingConfig.keyPass.getOrElse(signingConfig.storePass),
      signingConfig.alias)
    val creationData =
      new ApkCreatorFactory.CreationData(
        apk,
        certInfo.getKey, // key,
        certInfo.getCertificate, //certificate,
        signingConfig.v1, // v1SigningEnabled,
        signingConfig.v2, // v2SigningEnabled,
        null, // BuiltBy
        bldr.getCreatedBy,
        minSdkVersion,
        PackagingUtils.getNativeLibrariesLibrariesPackagingMode(manifest),
        PackagingUtils.getNoCompressPredicate(new AaptOptions {
          override def getNoCompress = null
          override def getFailOnMissingConfigEntry = true
          override def getAdditionalParameters = null
          override def getIgnoreAssets = null
        }, manifest))
    val incrementalApk = apk.getParentFile / "incremental"
    incrementalApk.mkdirs()
    val dexes = dexFolders.flatMap(d => (d ** "*.dex").get)
    val packager = new IncrementalPackager(
      creationData,
      incrementalApk, //getIncrementalFolder(),
      zfileCreatorFactory(debug),
      abiFilter.asJava,
      debug)
    packager.updateDex(
      deltas("incremental-apk-dex", dexes, f => f.getParentFile, s))
    packager.updateJavaResources(deltas("incremental-apk-javares",
      (javaResFolder ** FileOnlyFilter).get.toList, _ => javaResFolder, s))
    packager.updateAssets(deltas("incremental-apk-assets",
      (assetFolder ** FileOnlyFilter).get.toList, _ => assetFolder, s))
    packager.updateAndroidResources(IncrementalRelativeFileSets.fromZip(resapk))
    packager.updateNativeLibraries(deltas("incremental-apk-jni",
      (jniFolder ** FileOnlyFilter).get.toList, _ => jniFolder, s))
//    packager.updateAtomMetadata(changedAtomMetadata)
    packager.close()
  }

  def zfileCreatorFactory(debug: Boolean): ApkZFileCreatorFactory = {
    val keepTimestamps = false // TODO make user-configurable?

    val options = new ZFileOptions
    options.setNoTimestamps(!keepTimestamps)
    options.setCoverEmptySpaceUsingExtraField(true)

    /*
     * Work around proguard CRC corruption bug (http://b.android.com/221057).
     */
    options.setSkipDataDescriptionValidation(true)

    val compressionExecutor =
      new ThreadPoolExecutor(
        0, /* Number of always alive threads */
        2, //MAXIMUM_COMPRESSION_THREADS,
        100, //BACKGROUND_THREAD_DISCARD_TIME_MS,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingDeque)

    if (debug) {
      options.setCompressor(
        new DeflateExecutionCompressor(
          compressionExecutor,
          options.getTracker,
          Deflater.BEST_SPEED))
    } else {
      options.setCompressor(
        new BestAndDefaultDeflateExecutorCompressor(
          compressionExecutor,
          options.getTracker,
          1.0))
      options.setAutoSortFiles(true)
    }

    new ApkZFileCreatorFactory(options)
  }
  def sizeString(len: Long) = {
    val KB = 1024 * 1.0
    val MB = KB * KB
    len match {
      case s if s < MB  => "%.2fKB" format (s/KB)
      case s if s >= MB => "%.2fMB" format (s/MB)
    }
  }
  import collection.mutable
  def newMultiMap[K,V]: mutable.MultiMap[K,V] =
    new mutable.HashMap[K,mutable.Set[V]] with mutable.MultiMap[K,V] {
      override protected def makeSet = new mutable.LinkedHashSet[V]
    }
  def copyStream(in: InputStream, out: OutputStream, buf: Array[Byte]): Unit = {
    Iterator.continually(in.read(buf, 0, buf.length)).takeWhile(_ != -1).foreach { r =>
      out.write(buf, 0, r)
    }
  }
  case class ResourceCollector(validateJarPath:    String => Boolean,
                               validateFolderPath: String => Boolean,
                               folderPathToKey:    String => String,
                               keyToFolderPath:    String => String)
  /** ported from com.android.build.gradle.internal.transforms.MergeJavaResourcesTransform */
  // TODO implement incremental collection
  def collectNonAndroidResources(sources: Seq[File],
                                 dest: File,
                                 options: PackagingOptions,
                                 log: Logger,
                                 validator: ResourceCollector): Unit = {
    val excludes = options.excludes.toSet
    val firsts = options.pickFirsts.toSet
    val merges = options.merges.toSet
    IO.delete(dest)
    val srcdata = newMultiMap[String,File]
    dest.mkdirs()

    def skipEntry(entry: ZipEntry): Boolean =  {
      val path = entry.getName
      if (entry.isDirectory || JarFile.MANIFEST_NAME == path || !validator.validateJarPath(path)) {
        true
      } else {
        // split the path into segments.
        val segments = path.split("/")

        // empty path? skip to next entry.
        if (segments.isEmpty) {
          true
        } else {
          // Check each folders to make sure they should be included.
          // Folders like CVS, .svn, etc.. should already have been excluded from the
          // jar file, but we need to exclude some other folder (like /META-INF) so
          // we check anyway.
          segments.last.startsWith(".") || segments.last.startsWith("_") ||
            !PackagingUtils.checkFileForApkPackaging(segments.last, false /*allowClassFiles*/)
        }
      }
    }
    val (jars, dirs) = sources.filter(_.exists).partition(_.isFile)
    jars foreach { j =>
      Using.zipFile(j) { zipfile =>
        zipfile.entries.asScala.filterNot(skipEntry).foreach { e =>
          srcdata.addBinding(e.getName, j)
        }
      }
    }
    dirs foreach { d =>
      (d ** FileOnlyFilter).get flatMap (_ relativeTo d) map (_.getPath.replace('\\', '/')) filter
        validator.validateFolderPath foreach { f =>
        srcdata.addBinding(validator.folderPathToKey(f), d)
      }
    }

    val merged = newMultiMap[String,File]
    val fromjars = newMultiMap[File,String]
    srcdata.keys.filterNot(excludes).foreach { k =>
      val srcs = srcdata(k)
      val src = if (srcs.size == 1) {
        // TODO handle project provided files (always picked)
        srcs.headOption
      } else {
        if (firsts(k)) {
          srcs.headOption
        } else if (merges(k)) {
          merged += ((k, srcs))
          None
        } else {
          val e = new DuplicateFileException(k, srcs.toList.asJava)
          e.setStackTrace(Array.empty)
          throw e
        }
      }

      src.foreach { s =>
        if (s.isFile) {
          fromjars.addBinding(s, k)
        } else {
          val d = dest / k
          d.getParentFile.mkdirs()
          IO.copyFile(s / validator.keyToFolderPath(k), d, true)
        }
      }
    }

    val buf = Array.ofDim[Byte](32768)
    fromjars.keys.foreach { j =>
      val files = fromjars(j)
      Using.zipFile(j) { zipfile =>
        files foreach { f =>
          val d = dest / f
          if (!d.isDirectory) {
            d.getParentFile.mkdirs()
            Using.zipEntry(zipfile)(zipfile.getEntry(f)) { in =>
              Using.fileOutputStream(false)(d) { fout =>
                copyStream(in, fout, buf)
              }
            }
          }
        }
      }
    }

    merged.keys.foreach { k =>
      val files = merged(k)
      val d = dest / k
      d.getParentFile.mkdirs()
      Using.fileOutputStream(false)(d) { out =>
        files.foreach { f =>
          if (f.isDirectory) {
            Using.fileInputStream(f / validator.keyToFolderPath(k)) { in =>
              copyStream(in, out, buf)
            }
          } else {
            Using.zipFile(f) { zip =>
              Using.zipEntry(zip)(zip.getEntry(k)) { in =>
                copyStream(in, out, buf)
              }
            }
          }
        }
      }
    }
  }
}

