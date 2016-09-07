import sbt._
import sbt.Keys._
import android.Keys._
package object android {
  import android.Keys.Internal._
  def flavorOf(p: Project, id: String, settings: Setting[_]*): Project = {
    val base = p.base / id
    p.copy(id = id, base = base).settings(Seq(
      projectLayout := ProjectLayout(p.base.getCanonicalFile, Some(base.getCanonicalFile)),
      sbt.Keys.target := base) ++ settings:_*)
  }
  def withVariant(project: String,
                  buildType: Option[String] = None,
                  flavor: Option[String] = None): Setting[_] =
    sbt.Keys.onLoad in Global := (sbt.Keys.onLoad in Global).value andThen { s =>
      val ref = ProjectRef(Project.extract(s).structure.root, project)
      android.VariantSettings.withVariant(s) { variants =>
        if (!variants.status.contains(ref))
          android.VariantSettings.setVariant(s, ref, buildType, flavor)
        else s
      }
    }
  def withVariant(p: ProjectReference,
                  buildType: Option[String],
                  flavor: Option[String]): Setting[_] = withVariant(
    p match {
      case ProjectRef(_, id) => id
      case LocalProject(id)  => id
      case _ => fail("withVariant: Unsupported ProjectReference: " + p)
    },
    buildType, flavor)

  private[android] def buildWith(projects: Seq[ProjectReference]): Seq[Setting[_]] = {
    projects flatMap { p =>
      Seq(
        transitiveAars <++= aars in p,
        collectResources <<=
          collectResources dependsOn (compile in Compile in p),
        compile in Compile <<= compile in Compile dependsOn(
          packageT in Compile in p),
        localProjects +=
          LibraryProject((projectLayout in p).value, (extraResDirectories in p).value, (extraAssetDirectories in p).value)((outputLayout in p).value),
        localProjects := {
          (localProjects.value ++
            (localProjects in p).value).distinctLibs
        }
      )
    }
  }

  def useSupportVectors = Seq(
    renderVectorDrawables := false,
    aaptAdditionalParams += "--no-version-vectors"
  )
}