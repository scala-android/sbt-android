package android

import sbt._

object AndroidPlugin extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = plugins.JvmPlugin

  val autoImport = android.Keys
}
