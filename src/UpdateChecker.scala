package android

import java.io.{StringWriter, InputStreamReader}

import argonaut._, Argonaut._
import sbt.Logger

import scala.concurrent.Future

object UpdateChecker {
  import scala.concurrent.ExecutionContext.Implicits.global
  val bintray = new java.net.URL(
    "https://api.bintray.com/packages/pfn/sbt-plugins/android-sdk-plugin")
  def apply(log: Logger): Unit = {
    Future {
      val uc = bintray.openConnection()
      val in = new InputStreamReader(uc.getInputStream, "utf-8")
      val sw = new StringWriter
      val buf = Array.ofDim[Char](8192)
      Stream.continually(in.read(buf, 0, 8192)) takeWhile (
        _ != -1) foreach (sw.write(buf, 0, _))
      in.close()
      sw.toString
    } onSuccess {
      case json => json.decodeOption[PackageInfo] foreach { info =>
        // only notify if running a published version
        log.debug("available versions: " + info.versions)
        log.debug("current version: " + BuildInfo.version)
        log.debug("latest version: " + info.version)
        if (info.versions.toSet(BuildInfo.version)) {
          if (BuildInfo.version != info.version) {
            log.warn(
              s"UPDATE: A newer android-sdk-plugin is available:" +
                s" ${info.version}, currently running: ${BuildInfo.version}")
          }
        }
      }
    }
  }

  implicit def PackageInfoCodecJson: CodecJson[PackageInfo] = casecodec3(
    PackageInfo.apply, PackageInfo.unapply)(
      "name", "latest_version", "versions")

  case class PackageInfo(name: String, version: String, versions: List[String])
}