import sbt._
import sbt.Keys._
import android.Keys._
package object android extends PluginFail {
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

  private[android] def checkVersion(tag: String, version: String): Unit = {
    import scala.util.{Try, Success, Failure}
    Try(version.toInt) match {
      case Success(_) =>
      case Failure(_) => if (version.length > 1)
        PluginFail(tag + " must be an integer value or a single letter")
    }
  }
  private[android] def onLoadOnce(key: AnyRef)(f: State => State): State => State = state => {
    val stateKey = AttributeKey[Boolean](key + "-onLoadOnce4Android")
    if (!state.get(stateKey).getOrElse(false)) {
      f(state.put(stateKey, true))
    } else state
  }

  // conveniences follow
  def useSupportVectors = Seq(
    renderVectorDrawables := false,
    aaptAdditionalParams += "--no-version-vectors"
  )

  def useLibrary(library: String) =
    libraryRequests += ((library, true))

  def buildTools(version: String) =
    buildToolsVersion := Option(version)

  private def extendVariant(key: sbt.SettingKey[Map[String,Seq[Setting[_]]]], name: String, ss: Seq[Setting[_]]) =
    key <<= key { vs =>
      val ss2 = vs(name)
      vs + ((name, ss2 ++ ss))
    }

  def extendFlavor(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(flavors, name, ss)
  def flavor(name: String)(ss: Setting[_]*): Setting[_] =
    flavors += ((name, ss))

  def extendBuildType(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(buildTypes, name, ss)
  def buildType(name: String)(ss: Setting[_]*) =
    buildTypes += ((name, ss))

  def buildConfig(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    buildConfigOptions <+= value map { v => (`type`, name, v) }
  def buildConfig(`type`: String, name: String, value: String) =
    buildConfigOptions += ((`type`, name, value))

  def resValue(`type`: String, name: String, value: String) =
    resValues += ((`type`, name, value))
  def resValue(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    resValues <+= value map { v =>
      (`type`, name, v)
    }

  def signingConfig(keystore: File,
                    alias: String,
                    storePass: Option[String] = None,
                    keyPass: Option[String] = None,
                    singlePass: Boolean = true,
                    storeType: String = "jks") = {
    val sp = storePass orElse keyPass
    val config = if (sp.isEmpty) {
      if (singlePass)
        PromptStorepassSigningConfig(keystore, alias, storeType)
      else
        PromptPasswordsSigningConfig(keystore, alias, storeType)
    } else
      PlainSigningConfig(keystore, sp.get, alias, keyPass, storeType)

    apkSigningConfig := Some(config)
  }

  def apkExclude(name: String*) = packagingOptions := {
    val opts = packagingOptions.value
    opts.copy(excludes = opts.excludes ++ name)
  }
  def apkPickFirst(name: String*) = packagingOptions := {
    val opts = packagingOptions.value
    opts.copy(pickFirsts = opts.pickFirsts ++ name)
  }
  def apkMerge(name: String*) = packagingOptions := {
    val opts = packagingOptions.value
    opts.copy(merges = opts.merges ++ name)
  }

  def manifestPlaceholder(key: String, value: String) =
    manifestPlaceholders += ((key,value))
  def manifestPlaceholder(key: String, value: Def.Initialize[Task[String]]) =
    manifestPlaceholders <+= value map { v => (key,v) }
  def apkVersionName(name: String) = versionName := Option(name)
  def apkVersionCode(code: Int) = versionCode := Option(code)
  def apkVersionName(name: Def.Initialize[Task[String]]) = versionName <<= name map Option.apply
  def apkVersionCode(code: Def.Initialize[Task[Int]]) = versionCode <<= code map Option.apply
}