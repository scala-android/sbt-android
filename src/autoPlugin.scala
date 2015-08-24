package android

import Keys._
import sbt._
import sbt.Keys.onLoad

object AndroidPlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val autoImport = android.Keys

  override def buildSettings = Plugin.androidCommands

  override def globalSettings = (onLoad := onLoad.value andThen { s =>
    val e = Project.extract(s)
    val refs = e.currentRef +: e.currentProject.referenced collect {
      case ref if e.getOpt(projectLayout in (ref, Android)).isDefined => ref
    }
    val s2 = if (refs.nonEmpty) {
      e.runTask(updateCheck in (refs.head,Android), s)._1
    } else s

    refs.foldLeft(s2) { (s, ref) =>
      e.runTask(antLayoutDetector in (ref,Android), s)._1
    }
  }) :: Nil
}
