package android

import java.io.File

import android.Keys.PackagingOptions
import com.android.builder.core.AndroidBuilder
import com.android.builder.signing.DefaultSigningConfig
import com.android.ide.common.signing.KeystoreHelper
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
               collectJniOut: File, resFolder: File,
               debug: Boolean, output: File,
               logger: ILogger, s: sbt.Keys.TaskStreams): File = {

    import language.postfixOps
    if (isLib)
      Plugin.fail("This project cannot build APK, it has set 'libraryProject in Android := true'")
    val cacheDir = s.cacheDirectory
    val predexed = predex flatMap (_._2 * "*.dex" get)

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
    if (jniFolders.nonEmpty) {
      collectJniOut.mkdirs()
      val copyList = for {
        j <- jniFolders
        l <- (j ** "*.so").get ++ (j ** "gdbserver").get ++ (j ** "gdb.setup").get
      } yield (l, collectJniOut / (l relativeTo j).get.getPath)

      IO.copy(copyList)
    } else {
      IO.delete(collectJniOut)
    }
    val jniInputs = (collectJniOut ** new SimpleFileFilter(_.isFile)).get
    // end workaround

    s.log.debug("jars to process for resources: " + jars)

    val debugConfig = new DefaultSigningConfig("debug")
    debugConfig.initDebug()
    if (!debugConfig.getStoreFile.exists) {
      KeystoreHelper.createDebugStore(null, debugConfig.getStoreFile,
        debugConfig.getStorePassword, debugConfig.getKeyPassword,
        debugConfig.getKeyAlias, logger)
    }

    // filtering out org.scala-lang above should not cause an issue
    // they should not be changing on us anyway
    val deps = Set(shrinker +: ((dexFolder * "*.dex" get) ++ jars ++ jniInputs):_*)

    FileFunction.cached(cacheDir / output.getName, FilesInfo.hash) { in =>
      s.log.debug("bldr.packageApk(%s, %s, %s, null, %s, %s, %s, %s, %s)" format (
        shrinker.getAbsolutePath, dexFolder.getAbsolutePath, jars,
        if (collectJniOut.exists) Seq(collectJniOut).asJava else Seq.empty.asJava, debug,
        if (debug) debugConfig else null, output.getAbsolutePath,
        options
        ))
      bldr.packageApk(shrinker.getAbsolutePath, dexFolder, predexed.asJava, jars.asJava,
        resFolder.getAbsolutePath,
        (if (collectJniOut.exists) Seq(collectJniOut) else Seq.empty).asJava,
        s.cacheDirectory / "apkbuild-merging", null, debug,
        if (debug) debugConfig else null, options.asAndroid, output.getAbsolutePath)
      s.log.debug("Including predexed: " + predexed)
      s.log.info("Packaged: %s (%s)" format (
        output.getName, sizeString(output.length)))
      Set(output)
    }(deps).head
  }
  def sizeString(len: Long) = {
    val KB = 1024 * 1.0
    val MB = KB * KB
    len match {
      case s if s < MB  => "%.2fKB" format (s/KB)
      case s if s >= MB => "%.2fMB" format (s/MB)
    }
  }
}
