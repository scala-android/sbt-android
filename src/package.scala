import sbt.{Def, _}
import sbt.Keys._
import android.Keys._
package object android extends PluginFail {
  import android.Keys.Internal._

  @deprecated("use `android.flavor` and `android.buildType` instead", "1.7.0")
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
        setDebug           := { setDebug dependsOn (setDebug in p) }.value,
        setRelease         := { setRelease dependsOn (setRelease in p) }.value,
        transitiveAars    ++= { aars in p }.value,
        collectResources   := { collectResources dependsOn (compile in Compile in p) }.value,
        compile in Compile := { compile in Compile dependsOn(packageT in Compile in p) }.value,
        localProjects +=
          LibraryProject((projectLayout in p).value, (extraResDirectories in p).value, (extraAssetDirectories in p).value)((outputLayout in p).value),
        localProjects := { (localProjects.value ++ (localProjects in p).value).distinctLibs }
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

  private[android] def onReload(key: AnyRef)(f: State => State): State => State = state => {
    (if (!state.get(VariantSettings.onUnloadSkip).getOrElse(false)) {
      val stateKey = AttributeKey[Boolean](key + "-onLoadOnce4Android")
      f(state.remove(stateKey))
    } else state).remove(VariantSettings.onUnloadSkip)
  }

  // conveniences follow
  def useSupportVectors = Seq(
    renderVectorDrawables := false,
    aaptAdditionalParams += "--no-version-vectors"
  )

  def useLibrary(library: String): Def.Setting[Seq[(String, Boolean)]] =
    libraryRequests += ((library, true))

  def buildTools(version: String): Def.Setting[Option[String]] =
    buildToolsVersion := Option(version)

  private def extendVariant(key: sbt.SettingKey[Map[String,Seq[Setting[_]]]], name: String, ss: Seq[Setting[_]]) =
    key := key { vs =>
      val ss2 = vs(name)
      vs + ((name, ss2 ++ ss))
    }.value

  def extendFlavor(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(flavors, name, ss)
  def flavor(name: String)(ss: Setting[_]*): Setting[_] =
    flavors += ((name, ss))

  def extendBuildType(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(buildTypes, name, ss)
  def buildType(name: String)(ss: Setting[_]*): Def.Setting[Map[String, Seq[Setting[_]]]] =
    buildTypes += ((name, ss))

  def buildConfig(`type`: String, name: String, value: Def.Initialize[Task[String]]): Def.Setting[Task[Seq[(String, String, String)]]] =
    buildConfigOptions += { value map { v => (`type`, name, v) }}.value
  def buildConfig(`type`: String, name: String, value: String): Def.Setting[Task[Seq[(String, String, String)]]] =
    buildConfigOptions += ((`type`, name, value))

  def resValue(`type`: String, name: String, value: String): Def.Setting[Task[Seq[(String, String, String)]]] =
    resValues += ((`type`, name, value))
  def resValue(`type`: String, name: String, value: Def.Initialize[Task[String]]): Def.Setting[Task[Seq[(String, String, String)]]] =
    resValues += { value map { v => (`type`, name, v) } }.value

  def signingConfig(keystore: File,
                    alias: String,
                    storePass: Option[String] = None,
                    keyPass: Option[String] = None,
                    singlePass: Boolean = true,
                    storeType: String = "jks"): Def.Setting[Option[ApkSigningConfig]] = {
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

  def apkDoNotStrip(name: String*): Def.Setting[PackagingOptions] = packagingOptions := {
    val opts = packagingOptions.value
    opts.copy(doNotStrip = opts.doNotStrip ++ name)
  }

  def apkExclude(name: String*): Def.Setting[PackagingOptions] = packagingOptions := {
    val opts = packagingOptions.value
    opts.copy(excludes = opts.excludes ++ name)
  }
  def apkPickFirst(name: String*): Def.Setting[PackagingOptions] = packagingOptions := {
    val opts = packagingOptions.value
    opts.copy(pickFirsts = opts.pickFirsts ++ name)
  }
  def apkMerge(name: String*): Def.Setting[PackagingOptions] = packagingOptions := {
    val opts = packagingOptions.value
    opts.copy(merges = opts.merges ++ name)
  }

  def manifestPlaceholder(key: String, value: String): Def.Setting[Task[Map[String, String]]] =
    manifestPlaceholders += ((key,value))
  def manifestPlaceholder(key: String, value: Def.Initialize[Task[String]]): Def.Setting[Task[Map[String, String]]] =
    manifestPlaceholders += (value map { v => (key,v) }).value
  def apkVersionName(name: String): Def.Setting[Task[Option[String]]] =
    versionName := Option(name)
  def apkVersionCode(code: Int): Def.Setting[Task[Option[Int]]] =
    versionCode := Option(code)
  def apkVersionName(name: Def.Initialize[Task[String]]): Def.SettingsDefinition =
    versionName := (name map Option.apply).value
  def apkVersionCode(code: Def.Initialize[Task[Int]]) =
    versionCode := (code map Option.apply).value
}
