package android

import java.io.{InputStream, OutputStream, FileOutputStream, File}
import java.util.jar.JarFile
import java.util.zip.{ZipEntry, ZipFile}

import android.Keys.PackagingOptions
import com.android.SdkConstants
import com.android.builder.core.AndroidBuilder
import com.android.builder.packaging.DuplicateFileException
import com.android.ide.common.packaging.PackagingUtils
import com.android.utils.ILogger
import sbt.Def.Classpath
import sbt.Keys.moduleID
import sbt._
import collection.JavaConverters._

/**
 * @author pfnguyen
 */
object Packaging {

  def apkbuild(bldr: AndroidBuilder, m: Classpath, u: Classpath, dcp: Classpath,
               isLib: Boolean, options: PackagingOptions, shrinker: File,
               dexFolder: File, predex: Seq[(File,File)], jniFolders: Seq[File],
               collectJniOut: File, resFolder: File, collectResourceFolder: File,
               debug: Boolean, debugSigningConfig: ApkSigningConfig, output: File,
               logger: ILogger, s: sbt.Keys.TaskStreams): File = {

    import language.postfixOps
    if (isLib)
      Plugin.fail("This project cannot build APK, it has set 'libraryProject in Android := true'")
    val predexed = predex flatMap (_._2 * "*.dex" get) map (_.getParentFile)

    val jars = (m ++ u ++ dcp).filter {
      a => (a.get(moduleID.key) map { mid =>
        mid.organization != "org.scala-lang" &&
          !(mid.configurations exists (_ contains "provided"))
      } getOrElse true) && a.data.exists
    }.groupBy(_.data.getName).collect {
      case ("classes.jar",xs) => xs.distinct
      case (_,xs) if xs.head.data.isFile => xs.head :: Nil
    }.flatten.map (_.data).toList
    // filtering out org.scala-lang above should not cause an issue
    // they should not be changing on us anyway

    s.log.debug("jars to process for resources: " + jars)

    val jarAbiPattern = "lib/([^/]+)/[^/]+".r
    val folderAbiPattern = "([^/]+)/[^/]+".r
    val filenamePattern = ".*\\.so".r
    FileFunction.cached(s.cacheDirectory / "apkbuild-collect-resources", FilesInfo.lastModified) { _ =>
      collectNonAndroidResources(resFolder :: jars, collectResourceFolder, options, s.log, ResourceCollector(
        s => !s.endsWith(SdkConstants.DOT_CLASS) && !s.endsWith(SdkConstants.DOT_NATIVE_LIBS),
        s => !s.endsWith(SdkConstants.DOT_CLASS) && !s.endsWith(SdkConstants.DOT_NATIVE_LIBS),
        identity, identity
      ))
      (collectResourceFolder ** FileOnlyFilter).get.toSet
    }(((resFolder ** FileOnlyFilter).get ++ jars).toSet)
    FileFunction.cached(s.cacheDirectory / "apkbuild-collect-jni", FilesInfo.lastModified) { _ =>
      collectNonAndroidResources(jniFolders, collectJniOut, options, s.log, ResourceCollector(
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
    }(jniFolders.flatMap(_ ** FileOnlyFilter get).toSet)
    bldr.packageApk(shrinker.getAbsolutePath, (dexFolder +: predexed).toSet.asJava,
      List(collectResourceFolder).filter(_.exists).asJava,
      List(collectJniOut).filter(_.exists).asJava,
      Set.empty.asJava, debug,
      if (debug) debugSigningConfig.toSigningConfig("debug") else null, output.getAbsolutePath)
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
          Using.zipEntry(zipfile)(zipfile.getEntry(f)) { in =>
            val d = dest / f
            d.getParentFile.mkdirs()
            Using.fileOutputStream(false)(d) { fout =>
              copyStream(in, fout, buf)
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

