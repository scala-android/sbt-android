package android

import java.io.File
import java.net.URL

import com.android.repository.Revision
import com.android.repository.api._
import com.android.repository.impl.generated.generic.v1.GenericDetailsType
import com.android.repository.impl.generated.v1._
import com.android.repository.io.FileOp
import com.android.sdklib.internal.repository.{CanceledByUserException, DownloadCache}
import com.android.sdklib.repositoryv2.{AndroidSdkHandler, LegacyDownloader, LegacyTaskMonitor}
import com.android.repository.api.{RemotePackage => RepoRemotePackage}
import com.android.sdklib.repositoryv2.generated.addon.v1.{AddonDetailsType, ExtraDetailsType, LibrariesType}
import com.android.sdklib.repositoryv2.generated.common.v1.IdDisplayType
import com.android.sdklib.repositoryv2.generated.repository.v1.{LayoutlibType, PlatformDetailsType}
import com.android.sdklib.repositoryv2.generated.sysimg.v1.SysImgDetailsType
import sbt.{IO, Using, Logger}

import collection.JavaConverters._
import concurrent.duration._
import scala.xml.{Elem, Node, XML}

object SdkInstaller {
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
                     slog: Logger): RepoRemotePackage =
    install(sdkHandler, name, prefix, slog)(_.get(prefix + pkg))

  def install(sdkHandler: AndroidSdkHandler,
              name: String,
              prefix: String,
              slog: Logger)(pkgfilter: Map[String,RepoRemotePackage] => Option[RepoRemotePackage]): RepoRemotePackage = {
    val downloader = SbtAndroidDownloader(sdkHandler.getFileOp)
    val repomanager = sdkHandler.getSdkManager(PrintingProgressIndicator())
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(), downloader, null)
    val pkgs = repomanager.getPackages.getRemotePackages.asScala.toMap
    val remotepkg = pkgfilter(pkgs)
    remotepkg match {
      case None =>
        Plugin.fail(
          s"""No installable package found for $name
              |
              |Available packages:
              |${pkgs.keys.toList.filter(_.startsWith(prefix)).map(pkgs).sorted(platformOrder).map(
                 "  " + _.getPath.substring(prefix.length)).mkString("\n")}
           """.stripMargin)
      case Some(r) =>
        slog.info(s"Installing package '${r.getDisplayName}' ...")
        val installer = AndroidSdkHandler.findBestInstaller(r)
        val ind = PrintingProgressIndicator()
        val succ = installer.install(r, downloader, null, ind, repomanager, sdkHandler.getFileOp)
        if (!succ) Plugin.fail("SDK installation failed")
        if (ind.getFraction != 1.0)
          ind.setFraction(1.0) // workaround for installer stopping at 99%
        r
    }
  }

  def installSdkTaskDef = sbt.Def.inputTask {
    val toInstall = parsers.installSdkParser.parsed
    val log = sbt.Keys.streams.value.log
    val ind = SbtAndroidProgressIndicator(log)
    val sdkHandler = Keys.Internal.sdkManager.value
    val repomanager = sdkHandler.getSdkManager(ind)
    repomanager.loadSynchronously(1.day.toMillis,
      PrintingProgressIndicator(), SbtAndroidDownloader(sdkHandler.getFileOp), null)
    val newpkgs = repomanager.getPackages.getNewPkgs.asScala.filterNot(_.obsolete).toList.sorted(platformOrder)
    toInstall match {
      case Some(p) =>
        install(sdkHandler, p, "", log)(_.get(p))
        repomanager.loadSynchronously(0, ind, null, null)
      case None =>
        val packages = newpkgs.map { p =>
            val path = p.getPath
            val name = p.getDisplayName
            s"  $path\n  - $name"
          }
        log.error("Available packages:\n" + packages.mkString("\n"))
        Plugin.fail("A package to install must be specified")
    }
    ()
  }
  //noinspection MutatorLikeMethodIsParameterless
  def updateSdkTaskDef = sbt.Def.inputTask {
    val toUpdate = parsers.updateSdkParser.parsed
    val log = sbt.Keys.streams.value.log
    val ind = SbtAndroidProgressIndicator(log)
    val sdkHandler = Keys.Internal.sdkManager.value
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
          install(sdkHandler, u.getDisplayName, "", log)(_.get(u.getPath))
          repomanager.loadSynchronously(0, ind, null, null)
        }
      case None =>
        updatesHelp()
        Plugin.fail("A package or 'all' must be specified")
    }
    toUpdate.right.foreach { p =>
      updates.find(_.getPath == p) match {
        case Some(pkg) =>
          install(sdkHandler, pkg.getDisplayName, "", log)(_.get(pkg.getPath))
          repomanager.loadSynchronously(0, ind, null, null)
        case None =>
          updatesHelp()
          Plugin.fail(s"Update '$p' not found")
      }
    }
    ()
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
        log.warn("Android SDK updates available, run 'android:update-sdk' to update:")
        updates.foreach { u =>
          val p = u.getRemote
          log.warn("    " + p.getDisplayName)
        }
      }
    }
    ()
  }

}
object FallbackSdkLoader extends FallbackRemoteRepoLoader {
  override def parseLegacyXml(xml: RepositorySource, progress: ProgressIndicator) = {
    val repo = Using.urlReader(IO.utf8)(new URL(xml.getUrl))(XML.load)
    val sdkrepo = repo.child.foldLeft((Map.empty[String,License],List.empty[com.android.repository.api.RemotePackage])) {
      case ((ls,ps),e@Elem(prefix,
      "platform" | "platform-tool" | "build-tool" | "tool" | "system-image" | "add-on" | "extra",
      meta, ns, children @ _*)) =>
        (ls,processPackage(xml, ls, e).fold(ps)(_ :: ps))
      case ((ls,ps),e@Elem(prefix, "license", meta, ns, children @ _*)) =>
        val id = e.attribute("id").flatMap(_.headOption).map(_.text)
        (id.fold(ls)(i => ls + ((i,License(i,e.text)))),ps)
      case ((ls,ps),_) => (ls,ps)
    }
    sdkrepo._2.asJava
  }

  def processPackage(src: RepositorySource, ls: Map[String,License], e: Node): Option[com.android.repository.api.RemotePackage] = {
    val archive = (e \ "archives").headOption.toList.flatMap(a => processArchive(a, e.label == "platform"))
    val obsolete = (e \ "obsolete").nonEmpty
    for {
      d <- (e \ "description").headOption.map(_.text)
      r <- (e \ "revision").headOption
    } yield {
      val rev = processRevision(r)
      val (dep,tpe,path) = e.label match {
        case "platform"      =>
          val api = (e \ "api-level").headOption.fold("???")(_.text)
          val llapi = (e \ "layoutlib" \ "api").headOption.fold(-1)(_.text.toInt)
          val tpe = new PlatformDetailsType
          tpe.setApiLevel(api.toInt)
          val layoutlib = new LayoutlibType
          layoutlib.setApi(llapi)
          tpe.setLayoutlib(layoutlib)
          val d = (e \ "min-tools-rev").headOption.map { min =>
            val dep = new DependencyType
            val r = processRevision(min)
            dep.setPath("tools")
            val rev = new RevisionType
            rev.setMajor(r.getMajor)
            rev.setMinor(r.getMinor)
            rev.setMicro(r.getMicro)
            rev.setPreview(r.getPreview)
            dep.setMinRevision(rev)
            dep
          }
          (d,tpe,s"platforms;android-$api")
        case "platform-tool" =>
          (None,new GenericDetailsType,"platform-tools")
        case "system-image" =>
          val api = (e \ "api-level").headOption.fold(-1)(_.text.toInt)
          val tag = (e \ "tag-id").headOption.fold("???")(_.text)
          val tDi = (e \ "tag-display").headOption.fold("???")(_.text)
          val abi = (e \ "abi").headOption.fold("???")(_.text)
          val tpe = new SysImgDetailsType
          tpe.setAbi(abi)
          tpe.setApiLevel(api)
          val idD = new IdDisplayType
          idD.setId(tag)
          idD.setDisplay(tDi)
          tpe.setTag(idD)
          (None,tpe,s"system-images;android-$api;$tag;$abi")
        case "build-tool"    =>
          (None,new GenericDetailsType,"build-tools;" + rev.toShortString)
        case "tool"          =>
          (None,new GenericDetailsType,"tools;" + rev.toShortString)
        case "extra"         =>
          val path = (e \ "path").headOption.fold("???")(_.text)
          val vId = (e \ "vendor-id").headOption.fold("???")(_.text)
          val vDi = (e \ "vendor-display").headOption.fold("???")(_.text)
          val tpe = new ExtraDetailsType
          val idD = new IdDisplayType
          idD.setId(vId)
          idD.setDisplay(vDi)
          tpe.setVendor(idD)
          (None,tpe,s"extras;$vId;$path")
        case "add-on"        =>
          val api = (e \ "api-level").headOption.fold(-1)(_.text.toInt)
          val vId = (e \ "vendor-id").headOption.fold("???")(_.text)
          val vDi = (e \ "vendor-display").headOption.fold("???")(_.text)
          val nId = (e \ "name-id").headOption.fold("???")(_.text)
          val tpe = new AddonDetailsType
          tpe.setApiLevel(api.toInt)
          val idD = new IdDisplayType
          idD.setId(vId)
          idD.setDisplay(vDi)
          tpe.setVendor(idD)
          val libs = new LibrariesType
          // TODO implement LibraryType, but don't really care since we don't allow
          // installing any add-ons
          tpe.setLibraries(libs)
          (None,tpe,s"add-ons;addon-$nId-$vId-$api")
      }
      val licref = (e \ "uses-license").headOption.flatMap(_.attribute("ref")).fold("")(_.text)
      RemotePackage(path, d, rev, ls(licref), obsolete, archive, dep, tpe, src).asV1
    }
  }

  def processRevision(e: Node): Revision = {
    val maj = (e \ "major").headOption.map(_.text.toInt)
    val min = (e \ "minor").headOption.map(_.text.toInt: java.lang.Integer)
    val mic = (e \ "micro").headOption.map(_.text.toInt: java.lang.Integer)
    val pre = (e \ "preview").headOption.map(_.text.toInt: java.lang.Integer)
    maj.fold(new Revision(e.text.trim.toInt)) { m =>
      new Revision(m, min.orNull, mic.orNull, pre.orNull)
    }
  }

  def processArchive(e: Node, ignoreHost: Boolean): List[Archive] = {
    val archives = e.child.collect {
      case c@Elem(prefix, "archive", meta, ns, children @ _*) =>
        val size     = c \ "size"
        val checksum = c \ "checksum"
        val url      = c \ "url"
        (for {
          s  <- size.headOption.map(_.text)
          ck <- checksum.headOption.map(_.text)
          u  <- url.headOption.map(_.text)
        } yield ArchiveFile(ck, s.toLong, u)).map { f =>
          val a = Archive(f)
          val hostOs = (c \ "host-os").headOption.map(_.text)
          val hostBits = (c \ "host-bits").headOption.map(_.text.toInt)
          val a1 = hostOs.fold(a)(o => a.copy(hostOs = Some(o)))
          val a2 = hostBits.fold(a1)(b => a1.copy(hostBits = Some(b)))
          a2
        }
    }.flatten.toList
    if (ignoreHost && archives.forall(!_.asV1.isCompatible)) {
      archives.map(_.copy(hostOs = None, hostBits = None))
    } else archives
  }
}
case class RemotePackage(getPath: String,
                         getDisplayName: String,
                         getVersion: Revision,
                         getLicense: License,
                         obsolete: Boolean,
                         archives: List[Archive],
                         dependency: Option[Dependency],
                         getTypeDetails: TypeDetails,
                         getSource: RepositorySource
                        ) {
  def asV1 = {
    val r = new com.android.repository.impl.generated.v1.RemotePackage
    r.setTypeDetails(getTypeDetails)
    r.setPath(getPath)
    r.setDisplayName(getDisplayName)
    r.setVersion(getVersion)
    r.setLicense(getLicense.asV1)
    r.setObsolete(obsolete)
    if (getVersion.getPreview > 0) {
      val channel = new ChannelType
      val channelRef = new ChannelRefType
      channel.setId("2")
      channel.setValue("Dev")
      channelRef.setRef(channel)
      r.setChannelRef(channelRef)
    }
    val dt = new DependenciesType
    dt.getDependency.addAll(dependency.toList.asJava)
    r.setDependencies(dt)
    val as = new com.android.repository.impl.generated.v1.ArchivesType
    as.getArchive.addAll(archives.map(_.asV1).asJava)
    r.setArchives(as)
    r.setSource(getSource)
    r
  }
}

case class License(getId: String, getValue: String) {
  override def toString = s"License(id=$getId)"

  def asV1 = {
    val l = new com.android.repository.impl.generated.v1.LicenseType
    l.setId(getId)
    l.setValue(getValue)
    l
  }
}

case class Archive(getComplete: ArchiveFile, hostOs: Option[String] = None, hostBits: Option[Integer] = None) {
  def asV1 = {
    val a = new com.android.repository.impl.generated.v1.ArchiveType
    a.setComplete(getComplete.asV1)
    a.setHostOs(hostOs.orNull)
    a.setHostBits(hostBits.orNull)
    a
  }
}

case class ArchiveFile(getChecksum: String, getSize: Long, getUrl: String) {
  def asV1 = {
    val a = new com.android.repository.impl.generated.v1.CompleteType
    a.setChecksum(getChecksum)
    a.setSize(getSize)
    a.setUrl(getUrl)
    a
  }
}

case class SbtAndroidDownloader(fop: FileOp) extends LegacyDownloader(fop) {
  val mDownloadCache = new DownloadCache(fop, DownloadCache.Strategy.FRESH_CACHE)
  override def downloadFully(url: URL, settings: SettingsController, indicator: ProgressIndicator) = {
    val result = File
      .createTempFile("LegacyDownloader", System.currentTimeMillis.toString)
    val out = fop.newFileOutputStream(result)
    try {
      val downloadedResult =  mDownloadCache.openDirectUrl(url.toString, null, new LegacyTaskMonitor(indicator))
      val httpresult = downloadedResult.getSecond
      if (httpresult.getStatusLine.getStatusCode == 200) {
        val length = Option(httpresult.getFirstHeader("Content-Length")).map(_.getValue.toLong)
        val len = 65536
        val buf = Array.ofDim[Byte](len)
        indicator.setIndeterminate(length.isEmpty)
        indicator.setText("Downloading " + url.getFile)
        var read = 0
        Iterator.continually(downloadedResult.getFirst.read(buf, 0, len)).takeWhile(_ != -1).foreach { r =>
          read = read + r
          length foreach { l =>
            indicator.setFraction(read.toDouble / l)
          }
          out.write(buf, 0, r)
        }
        downloadedResult.getFirst.close()
        indicator.setFraction(1.0)
        out.close()
        result
      } else null
    } catch {
      case e: CanceledByUserException =>
        indicator.logInfo("The download was cancelled.");
        null
    }
  }
}
