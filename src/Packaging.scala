package android

import java.io.File

import android.Keys.PackagingOptions
import com.android.builder.core.AndroidBuilder
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
               _unused_collectJniOut: File, resFolder: File,
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

    s.log.debug("jars to process for resources: " + jars)

    // filtering out org.scala-lang above should not cause an issue
    // they should not be changing on us anyway

      bldr.packageApk(shrinker.getAbsolutePath, (dexFolder +: predexed).toSet.asJava, List(resFolder).asJava,
        jniFolders.asJava,
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
}
