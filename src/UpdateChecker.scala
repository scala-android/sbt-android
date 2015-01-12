package android

import sbt.Keys.TaskStreams

import dispatch._, Defaults._
import argonaut._, Argonaut._
import sbt.{Level, Logger}

object UpdateChecker {
  val bintray = url(
    "https://api.bintray.com/packages/pfn/sbt-plugins/android-sdk-plugin")
  def checkCurrent(log: Logger): Unit = {
    Http(bintray OK as.String) onSuccess {
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

  def checkCurrent(): Unit = {
    object L extends Logger {
      override def trace(t: => Throwable) = ???

      override def log(level: Level.Value, msg: => String) = println(s"[$level] $msg")

      override def success(message: => String) = ???
    }
    checkCurrent(L)
  }

  implicit def PackageInfoCodecJson: CodecJson[PackageInfo] = casecodec3(
    PackageInfo.apply, PackageInfo.unapply)(
      "name", "latest_version", "versions")

  case class PackageInfo(name: String, version: String, versions: List[String])
}