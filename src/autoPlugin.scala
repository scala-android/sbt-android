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

    val androids = e.structure.allProjects map (p => ProjectRef(e.structure.root, p.id)) filter {
      ref => e.getOpt(projectLayout in(ref, Android)).isDefined
    }
    val androidIds = androids.map(_.project).toSet

    def checkForExport(p: ProjectRef): Seq[ProjectRef] = {
      Project.getProject(p, e.structure).toSeq flatMap { prj =>
        val deps = prj.dependencies map (_.project)
        val nonAndroid = deps filterNot (prj => androidIds(prj.project))

        (deps flatMap checkForExport) ++ (nonAndroid filterNot (d => e.getOpt(sbt.Keys.exportJars in d) exists (_ == true)))
      }
    }
    androids flatMap { p =>
      checkForExport(p)
    } foreach { unexported =>
      s.log.warn(s"${unexported.project} is an Android dependency but does not specify `exportJars := true`")
    }

    val s2 = androids.headOption.fold(s)(a =>
      e.runTask(updateCheck in (a,Android), s)._1
    )

    androids.foldLeft(s2) { (s, ref) =>
      e.runTask(antLayoutDetector in (ref,Android), s)._1
    }
  }) :: Nil
}
