package android

import Keys._
import com.android.sdklib.IAndroidTarget
import com.android.sdklib.repositoryv2.AndroidSdkHandler
import sbt._
import sbt.Keys.onLoad

// TODO move `android:run`, `android:package`, `android:install`, `android:test`, etc. here
object AndroidApp extends AutoPlugin {
  override def requires = AndroidProject
}

// TODO refactor, AndroidPlugin = AndroidProject, remove app/lib specific settings
// AndroidProject should not have `android:package`, `android:run`
// etc.
// consider keeping common `android:test` in AndroidProject
object AndroidProject extends AutoPlugin {
  override def requires = AndroidPlugin
}

object AndroidPlugin extends AutoPlugin with AndroidProjectSettings {
  override def requires = AndroidGlobalPlugin
}

// AndroidLib should support `android:test` as well. no test, install, run, etc.
object AndroidLib extends AutoPlugin with AndroidLibSettings {
  override def requires = AndroidProject
}
// AndroidJar should support `android:test` as well. no test, install, run, etc.
object AndroidJar extends AutoPlugin with AndroidJarSettings {
  override def requires = AndroidProject
}

case object AndroidGlobalPlugin extends AutoPlugin {

  def onLoadOnce(key: AnyRef)(f: State => State): State => State = state => {
    val stateKey = AttributeKey[Boolean](key + "-onLoadOnce4Android")
    if (!state.get(stateKey).getOrElse(false)) {
      f(state.put(stateKey, true))
    } else state
  }

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val autoImport = android.Keys

  override def buildSettings = Commands.androidCommands

  override def projectConfigurations = AndroidTest :: Internal.AndroidInternal :: Nil

  override def globalSettings = (onLoad := onLoad.value andThen onLoadOnce(this){ s =>
    val e = Project.extract(s)

    val androids = e.structure.allProjects map (p => ProjectRef(e.structure.root, p.id)) filter {
      ref => e.getOpt(projectLayout in ref).isDefined
    }
    val androidSet = androids.toSet

    def checkAndroidDependencies(p: ProjectRef): (ProjectRef,Seq[ProjectRef]) = {
      (p,Project.getProject(p, e.structure).toSeq flatMap { prj =>
        val deps = prj.dependencies map (_.project)
        val locals = Project.extract(s).get(localProjects in p).map(
          _.path.getCanonicalPath).toSet
        val depandroids = deps filter (prj => androidSet(prj))
        depandroids filterNot (a => Project.getProject(a, e.structure).exists (d =>
          locals(d.base.getCanonicalPath)))
      })
    }
    def checkForExport(p: ProjectRef): Seq[ProjectRef] = {
      Project.getProject(p, e.structure).toSeq flatMap { prj =>
        val deps = prj.dependencies map (_.project)
        val nonAndroid = deps filterNot (prj => androidSet(prj))

        (deps flatMap checkForExport) ++ (nonAndroid filterNot (d => e.getOpt(sbt.Keys.exportJars in d) exists (_ == true)))
      }
    }
    val addDeps = androids map checkAndroidDependencies map { case (p, dep) =>
      p -> android.buildWith(dep).map(VariantSettings.fixProjectScope(p))
    }
    androids flatMap checkForExport foreach { unexported =>
      s.log.warn(s"${unexported.project} is an Android dependency but does not specify `exportJars := true`")
    }

    val s2 = androids.headOption.fold(s) { a =>
      val s3 = e.runTask(updateCheck in a, s)._1
      e.runTask(updateCheckSdk in a, s3)._1
    }

    val end = androids.foldLeft(s2) { (s, ref) =>
      e.runTask(antLayoutDetector in ref, s)._1
    }
    if (addDeps.flatMap(_._2).nonEmpty) {
      s.log.info(s"Adding subproject dependency rules for: ${addDeps.map(_._1.project).mkString(", ")}")
      e.append(addDeps.flatMap(_._2), end)
    } else end
  }) :: Nil

  def platformTarget(targetHash: String, sdkHandler: AndroidSdkHandler, showProgress: Boolean, slog: Logger): IAndroidTarget = {
    SdkInstaller.retryWhileFailed("determine platform target", slog) {
      val manager = sdkHandler.getAndroidTargetManager(SbtAndroidProgressIndicator(slog))
      val ptarget = manager.getTargetFromHashString(targetHash, SbtAndroidProgressIndicator(slog))
      SdkInstaller.autoInstallPackage(sdkHandler, "platforms;", targetHash, targetHash, showProgress, slog, _ => ptarget == null)
      manager.getTargetFromHashString(targetHash, SbtAndroidProgressIndicator(slog))
    }
  }
}
