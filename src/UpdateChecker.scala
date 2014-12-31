package android

import sbt.Keys.TaskStreams

import dispatch._, Defaults._
import argonaut._, Argonaut._

object UpdateChecker {
  val bintray = url(
    "https://api.bintray.com/packages/pfn/sbt-plugins/android-sdk-plugin")
  def checkCurrent(s: TaskStreams): Unit = {
    Http(bintray OK as.String) onSuccess {
      case json => json.decodeOption[PackageInfo] foreach { info =>
        // only notify if running a published version
        if (info.versions.toSet(BuildInfo.version)) {
          if (BuildInfo.version != info.version) {
            s.log.warn(
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