package android

import java.io.{File, InputStream, OutputStream}
import java.util.jar.JarFile
import java.util.zip.ZipEntry

import android.Keys.PackagingOptions
import com.android.SdkConstants
import com.android.builder.core.AndroidBuilder
import com.android.builder.packaging.DuplicateFileException
import com.android.ide.common.packaging.PackagingUtils
import com.android.utils.ILogger
import sbt.Def.Classpath
import sbt.Keys.moduleID
import sbt._, syntax._, io.Using

import scala.collection.JavaConverters._


/**
 * @author pfnguyen
 */
object Packaging {
  case class Jars( managed: Classpath
                 , unmanaged: Classpath
                 , depenendices: Classpath) {

    def isScalaLang(module: ModuleID) = module.organization == "org.scala-lang"
    def isProvidedDependency(module: ModuleID) = module.configurations exists (_ contains "provided")

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
              , output: File
              , logger: ILogger
              , s: sbt.Keys.TaskStreams): File = {
    apkbuild(
      bldr, Jars(managed, unmanaged, dependenciesCp), isLib,
      aggregateOptions, abiFilter, collectJniOut, resFolder,
      collectResourceFolder, output, logger, s
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
      PluginFail("This project cannot build APK, it has set 'libraryProject in Android := true'")
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
    bldr.packageApk(shrinker.getAbsolutePath, (dexFolder +: predexed).toSet.asJava,
      List(collectResourceFolder).filter(_.exists).asJava,
      List(collectJniOut).filter(_.exists).asJava,
      abiFilter.asJava, debug,
      if (debug) debugSigningConfig.toSigningConfig("debug") else null,
      output.getAbsolutePath, minSdk)
    s.log.debug("Including predexed: " + predexed)
    s.log.info("Packaged: %s (%s)" format (
      output.getName, sizeString(output.length)))
    output
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
          !segments.exists(PackagingUtils.checkFolderForPackaging) ||
            !PackagingUtils.checkFileForPackaging(segments.last, false /*allowClassFiles*/ )
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

object SignAndroidJar {
  final class SignOption private[SignAndroidJar] (val toList: List[String], val signOnly: Boolean) {
    override def toString = toList.mkString(" ")
  }
  def keyStore(url: URL): SignOption = new SignOption("-keystore" :: url.toExternalForm :: Nil, true)
  def signedJar(p: File): SignOption = new SignOption("-signedjar" :: p.getAbsolutePath :: Nil, true)
  def verbose = new SignOption("-verbose" :: Nil, false)
  def sigFile(name: String) = new SignOption("-sigfile" :: name :: Nil, true)
  def storeType(t: String): SignOption = new SignOption("-storetype" :: t :: Nil, false)
  def provider(p: String) = new SignOption("-provider" :: p :: Nil, false)
  def providerName(p: String) = new SignOption("-providerName" :: p :: Nil, false)
  def storePassword(p: String): SignOption = new SignOption("-storepass" :: p :: Nil, true)
  def keyPassword(p: String): SignOption = new SignOption("-keypass" :: p :: Nil, true)

  private def VerifyOption = "-verify"

  /** Uses jarsigner to sign the given jar.  */
  def sign(jarPath: File, alias: String, options: Seq[SignOption])(fork: (String, List[String]) => Int) {
    require(!alias.trim.isEmpty, "Alias cannot be empty")
    val arguments = options.toList.flatMap(_.toList) ::: jarPath.getAbsolutePath :: alias :: Nil
    execute("signing", arguments)(fork)
  }
  /** Uses jarsigner to verify the given jar.*/
  def verify(jarPath: File, options: Seq[SignOption])(fork: (String, List[String]) => Int) {
    val arguments = options.filter(!_.signOnly).toList.flatMap(_.toList) ::: VerifyOption :: jarPath.getAbsolutePath :: Nil
    execute("verifying", arguments)(fork)
  }
  private def execute(action: String, arguments: List[String])(fork: (String, List[String]) => Int) {
    val exitCode = fork(CommandName, arguments)
    if (exitCode != 0)
      sys.error("Error " + action + " jar (exit code was " + exitCode + ".)")
  }

  private val CommandName = "jarsigner"
}
