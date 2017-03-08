package android

import java.io.File
import java.net.{HttpURLConnection, URL}

import com.android.repository.api._
import com.android.repository.impl.generated.v1.{LocalPackage => _, _}
import com.android.repository.io.FileOp
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.repository.api.{RemotePackage => RepoRemotePackage}
import com.android.sdklib.repository.generated.repository.v1.PlatformDetailsType
import com.android.sdklib.repository.installer.SdkInstallerUtil
import sbt.{IO, Logger, Using}

import collection.JavaConverters._
import concurrent.duration._

object SdkInstaller extends TaskBase {
  implicit val packageOrder: Ordering[com.android.repository.api.RemotePackage] =
    new Ordering[com.android.repository.api.RemotePackage] {
      override def compare(x: com.android.repository.api.RemotePackage,
                           y: com.android.repository.api.RemotePackage) = y.compareTo(x)
    }
  val platformOrder: Ordering[com.android.repository.api.RemotePackage] =
    new Ordering[com.android.repository.api.RemotePackage] {
      override def compare(x: RepoRemotePackage, y: RepoRemotePackage) = (x.getTypeDetails,y.getTypeDetails) match {
        case (a: PlatformDetailsType, b: PlatformDetailsType) =>
          b.getApiLevel - a.getApiLevel
        case _ => y.compareTo(x)
      }
    }
  def installPackage(sdkHandler: AndroidSdkHandler,
                     prefix: String,
                     pkg: String,
                     name: String,
                     showProgress: Boolean,
                     slog: Logger): RepoRemotePackage =
    install(sdkHandler, name, prefix, showProgress, slog)(_.get(prefix + pkg))

  def autoInstallPackage(sdkHandler: AndroidSdkHandler,
                         prefix: String,
                         pkg: String,
                         name: String,
                         showProgress: Boolean,
                         slog: Logger): Option[RepoRemotePackage] =
    autoInstallPackage(sdkHandler, prefix, pkg, name, showProgress, slog, !_.contains(pkg))
  def autoInstallPackage(sdkHandler: AndroidSdkHandler,
                         prefix: String,
                         pkg: String,
                         name: String,
                         showProgress: Boolean,
                         slog: Logger,
                         pred: Map[String,LocalPackage] => Boolean): Option[RepoRemotePackage] = {
    val ind = SbtAndroidProgressIndicator(slog)
    val pkgs = retryWhileFailed("get local packages", slog)(
      sdkHandler.getSdkManager(ind).getPackages.getLocalPackages.asScala.toMap
    )
    if (pred(pkgs)) {
      slog.warn(s"$name not found, searching for package...")
      Option(installPackage(sdkHandler, prefix, pkg, name, showProgress, slog))
    } else {
      None
    }
  }
  def autoInstall(sdkHandler: AndroidSdkHandler,
                  name: String,
                  prefix: String,
                  showProgress: Boolean,
                  slog: Logger,
                  pred: Map[String,LocalPackage] => Boolean)(pkgfilter: Map[String,RepoRemotePackage] => Option[RepoRemotePackage]): Option[RepoRemotePackage] = {
    val ind = SbtAndroidProgressIndicator(slog)
    val pkgs = retryWhileFailed("get local packages", slog)(
      sdkHandler.getSdkManager(ind).getPackages.getLocalPackages.asScala.toMap
    )
    if (pred(pkgs)) {
      slog.warn(s"$name not found, searching for package...")
      Option(install(sdkHandler, prefix, name, showProgress, slog)(pkgfilter))
    } else {
      None
    }
  }

  def platforms(sdkHandler: AndroidSdkHandler,
                showProgress: Boolean
               ) = {
    val repomanager = sdkHandler.getSdkManager(PrintingProgressIndicator(showProgress))
    val downloader = SbtAndroidDownloader(sdkHandler.getFileOp)
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(showProgress), downloader, null)
    val pkgs = repomanager.getPackages.getRemotePackages.asScala.toMap.collect {
      case (k,v) if k.startsWith("platforms;") => v
    }.toList.sorted(platformOrder).map(_.getPath.stripPrefix("platforms;"))
    pkgs
  }

  def install(sdkHandler: AndroidSdkHandler,
              name: String,
              prefix: String,
              showProgress: Boolean,
              slog: Logger)(pkgfilter: Map[String,RepoRemotePackage] => Option[RepoRemotePackage]): RepoRemotePackage = synchronized {
    val downloader = SbtAndroidDownloader(sdkHandler.getFileOp)
    val repomanager = sdkHandler.getSdkManager(PrintingProgressIndicator(showProgress))
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(showProgress), downloader, null)
    val pkgs = repomanager.getPackages.getRemotePackages.asScala.toMap
    val remotepkg = pkgfilter(pkgs)
    remotepkg match {
      case None =>
        PluginFail(
          s"""No installable package found for $name
              |
              |Available packages:
              |${pkgs.keys.toList.filter(_.startsWith(prefix)).map(pkgs).sorted(platformOrder).map(
                 "  " + _.getPath.substring(prefix.length)).mkString("\n")}
           """.stripMargin)
      case Some(r) =>
        Option(r.getLicense).foreach { l =>
          val id = l.getId
          val license = l.getValue
          println(s"By continuing to use sbt-android, you accept the terms of '$id'")
          println(s"You may review the terms by running 'android-license $id'")
          val f = java.net.URLEncoder.encode(id, "utf-8")
          SdkLayout.sdkLicenses.mkdirs()
          IO.write(new File(SdkLayout.sdkLicenses, f), license)
        }
        val installed = repomanager.getPackages.getLocalPackages.asScala.get(r.getPath)
        if (installed.forall(_.getVersion != r.getVersion)) {
          slog.info(s"Installing package '${r.getDisplayName}' ...")
          val installerF = SdkInstallerUtil.findBestInstallerFactory(r, sdkHandler)
          val installer = installerF.createInstaller(r, repomanager, downloader, sdkHandler.getFileOp)
          val ind = PrintingProgressIndicator(showProgress)
          val succ = installer.prepare(ind) && installer.complete(ind)
          if (!succ) PluginFail("SDK installation failed")
          if (ind.getFraction != 1.0)
            ind.setFraction(1.0) // workaround for installer stopping at 99%
          // force RepoManager to clear itself
          sdkHandler.getSdkManager(ind).loadSynchronously(0, ind, null, null)
        } else {
          slog.warn(s"'${r.getDisplayName}' already installed, skipping")
        }
        r
    }
  }

  def retryWhileFailed[A](err: String, log: Logger, delay: Int = 250)(f: => A): A = {
    Iterator.continually(util.Try(f)).dropWhile { t =>
      val failed = t.isFailure
      if (failed) {
        log.error(s"Failed to $err, retrying...")
        Thread.sleep(delay)
      }
      failed
    }.next.get
  }

  def sdkPath(slog: sbt.Logger, props: java.util.Properties): String = {
    val cached = SdkLayout.androidHomeCache
    val path = (Option(System getenv "ANDROID_HOME") orElse
      Option(props getProperty "sdk.dir")) flatMap { p =>
      if (p startsWith "~")
        slog.warn("use $HOME instead of ~ when setting ANDROID_HOME")
      val f = sbt.file(p)
      if (f.exists && f.isDirectory) {
        cached.getParentFile.mkdirs()
        IO.writeLines(cached, p :: Nil)
        Some(p)
      } else None
    } orElse SdkLayout.sdkFallback(cached) getOrElse {
      val home = SdkLayout.fallbackAndroidHome
      slog.info("ANDROID_HOME not set or invalid, using " +
        home.getCanonicalPath)
      home.mkdirs()
      home.getCanonicalPath
    }
    sys.props("com.android.tools.lint.bindir") = SdkLayout.tools(path).getCanonicalPath
    path
  }

  private def sdkpath(state: sbt.State): String =
    sdkPath(state.log, loadProperties(sbt.Project.extract(state).currentProject.base))

  private[this] lazy val sdkMemo = scalaz.Memo.immutableHashMapMemo[File, (Boolean, Logger) => AndroidSdkHandler] { f =>
    val manager = AndroidSdkHandler.getInstance(f)
    if (!SdkLayout.repocfg.isFile) {
      println(s"Android SDK repository config ${SdkLayout.repocfg.getPath} does not exist, creating")
      IO.delete(SdkLayout.repocfg)
      IO.touch(SdkLayout.repocfg)
    }

    (showProgress, slog) => manager.synchronized {
      SdkInstaller.autoInstallPackage(manager, "", "tools", "android sdk tools", showProgress, slog)
      SdkInstaller.autoInstallPackage(manager, "", "platform-tools", "android platform-tools", showProgress, slog)
      manager
    }
  }

  def sdkManager(path: File, showProgress: Boolean, slog: Logger): AndroidSdkHandler = synchronized {
    sdkMemo(path)(showProgress, slog)
  }

  def installSdkAction: (sbt.State, Option[String]) => sbt.State = (state,toInstall) => {
    val log = state.log
    val ind = SbtAndroidProgressIndicator(log)
    val sdkHandler = sdkManager(sbt.file(sdkpath(state)), true, log)
    val repomanager = sdkHandler.getSdkManager(ind)
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(), SbtAndroidDownloader(sdkHandler.getFileOp), null)
    val newpkgs = repomanager.getPackages.getNewPkgs.asScala.filterNot(_.obsolete).toList.sorted(platformOrder)
    toInstall match {
      case Some(p) =>
        install(sdkHandler, p, "", true, log)(_.get(p))
      case None =>
        val packages = newpkgs.map { p =>
            val path = p.getPath
            val name = p.getDisplayName
            s"  $path\n  - $name"
          }
        log.error("Available packages:\n" + packages.mkString("\n"))
        PluginFail("A package to install must be specified")
    }
    state
  }
  //noinspection MutatorLikeMethodIsParameterless
  def updateSdkAction: (sbt.State, Either[Option[String],String]) => sbt.State = (state,toUpdate) => {
    val log = state.log
    val ind = SbtAndroidProgressIndicator(log)
    val sdkHandler = sdkManager(sbt.file(sdkpath(state)), true, log)
    val repomanager = sdkHandler.getSdkManager(ind)
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(), SbtAndroidDownloader(sdkHandler.getFileOp), null)
    val updates = repomanager.getPackages.getUpdatedPkgs.asScala.collect {
      case u if u.hasRemote => u.getRemote }.toList.sorted(platformOrder)
    def updatesHelp(): Unit = {
      val packages = "  all\n  - apply all updates" ::
        updates.map { u =>
          val path = u.getPath
          val name = u.getDisplayName
          s"  $path\n  - $name"
        }
      if (packages.size == 1) {
        log.error("No updates available")
      } else {
        log.error("Available updates:\n" + packages.mkString("\n"))
      }
    }
    toUpdate.left.foreach {
      case Some(_) =>
        updates.foreach { u =>
          install(sdkHandler, u.getDisplayName, "", true, log)(_.get(u.getPath))
        }
      case None =>
        updatesHelp()
        PluginFail("A package or 'all' must be specified")
    }
    toUpdate.right.foreach { p =>
      updates.find(_.getPath == p) match {
        case Some(pkg) =>
          install(sdkHandler, pkg.getDisplayName, "", true, log)(_.get(pkg.getPath))
        case None =>
          updatesHelp()
          PluginFail(s"Update '$p' not found")
      }
    }
    state
  }
  //noinspection MutatorLikeMethodIsParameterless
  def updateCheckSdkTaskDef = sbt.Def.task {
    import concurrent.ExecutionContext.Implicits.global
    concurrent.Future {
      val sdkHandler = Keys.Internal.sdkManager.value
      val log = sbt.Keys.streams.value.log
      val ind = SbtAndroidProgressIndicator(log)
      val repomanager = sdkHandler.getSdkManager(ind)
      repomanager.loadSynchronously(1.day.toMillis, ind, SbtAndroidDownloader(sdkHandler.getFileOp), null)
      val updates = repomanager.getPackages.getUpdatedPkgs.asScala.filter(_.hasRemote)
      if (updates.nonEmpty) {
        log.warn("Android SDK updates available, run 'android-update' to update:")
        updates.foreach { u =>
          val p = u.getRemote
          log.warn("    " + p.getDisplayName)
        }
      }
    }
    ()
  }

}

// provide our own implementation of Downloader for progress indication
case class SbtAndroidDownloader(fop: FileOp) extends Downloader {
  override def downloadFully(url: URL, indicator: ProgressIndicator) = {
    val result = File
      .createTempFile("LegacyDownloader", System.currentTimeMillis.toString)
    result.deleteOnExit()
    downloadFully(url, result, null, indicator)
    result
  }


  override def downloadFully(url: URL, target: File, checksum: String, indicator: ProgressIndicator): Unit = {
    val alreadyDownloaded = if (fop.exists(target) && checksum != null) {
      Using.fileInputStream(target) { in =>
        checksum == Downloader.hash(in, fop.length(target), indicator)
      }
    } else false

    if (!alreadyDownloaded) {
      val out = fop.newFileOutputStream(target)
      val uc = url.openConnection().asInstanceOf[HttpURLConnection]
      val responseCode = uc.getResponseCode
      if (responseCode == 200) {
        val length = uc.getContentLength
        val in = uc.getInputStream
        Using.bufferedInputStream(in) { b =>
          Using.bufferedOutputStream(out) { o =>
            val len = 65536
            val buf = Array.ofDim[Byte](len)
            indicator.setIndeterminate(length == -1)
            indicator.setText("Downloading " + url.getFile)
            var read = 0
            Iterator.continually(in.read(buf, 0, len)).takeWhile(_ != -1).foreach { r =>
              read = read + r
              if (length != -1)
                indicator.setFraction(read.toDouble / length)
              o.write(buf, 0, r)
            }
            indicator.setFraction(1.0)
            o.close()
          }
        }
      }
    }
  }

  //noinspection JavaAccessorMethodCalledAsEmptyParen
  override def downloadAndStream(url: URL,
                                 indicator: ProgressIndicator) = url.openConnection().getInputStream()

}
