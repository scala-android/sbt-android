package android

import java.net.URL

import com.android.repository.Revision
import com.android.repository.api._
import com.android.repository.impl.generated.generic.v1.GenericDetailsType
import com.android.repository.impl.generated.v1._
import com.android.sdklib.repositoryv2.generated.addon.v1.{AddonDetailsType, ExtraDetailsType, LibrariesType}
import com.android.sdklib.repositoryv2.generated.common.v1.IdDisplayType
import com.android.sdklib.repositoryv2.generated.repository.v1.{LayoutlibType, PlatformDetailsType}
import com.android.sdklib.repositoryv2.generated.sysimg.v1.SysImgDetailsType
import sbt.{IO, Using}

import collection.JavaConverters._
import scala.xml.{Elem, Node, XML}

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
    val archive = (e \ "archives").headOption.flatMap(a => processArchive(a, e.label == "platform"))
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
      RemotePackage(path, d, rev, ls(licref), obsolete, archive.orNull, dep, tpe, src).asV1
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

  def processArchive(e: Node, ignoreHost: Boolean): Option[Archive] = {
    e.child.collect {
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
          if (!ignoreHost) {
            val hostOs = (c \ "host-os").headOption.map(_.text)
            val hostBits = (c \ "host-bits").headOption.map(_.text.toInt)
            val a1 = hostOs.fold(a)(o => a.copy(hostOs = Some(o)))
            val a2 = hostBits.fold(a1)(b => a1.copy(hostBits = Some(b)))
            a2
          } else a
        }
    }.flatten.find(_.asV1.isCompatible)
  }
}
case class RemotePackage(getPath: String,
                         getDisplayName: String,
                         getVersion: Revision,
                         getLicense: License,
                         obsolete: Boolean,
                         getArchive: Archive,
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
    val dt = new DependenciesType
    dt.getDependency.addAll(dependency.toList.asJava)
    r.setDependencies(dt)
    val as = new com.android.repository.impl.generated.v1.ArchivesType
    as.getArchive.add(getArchive.asV1)
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
