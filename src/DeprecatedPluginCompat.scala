package android
import sbt._
/**
  * @author pfnguyen
  */
trait DeprecatedPluginCompat {
  @deprecated("use `android.flavorOf`", "1.7.0")
  def flavorOf(p: Project, id: String, settings: Setting[_]*): Project = android.flavorOf(p, id, settings:_*)

  @deprecated("use `android.withVariant`", "1.7.0")
  def withVariant(project: String,
                  buildType: Option[String] = None,
                  flavor: Option[String] = None): Setting[_] =
    android.withVariant(project, buildType, flavor)

  @deprecated("use `android.withVariant`", "1.7.0")
  def withVariant(p: ProjectReference,
                  buildType: Option[String],
                  flavor: Option[String]): Setting[_] = android.withVariant(p, buildType, flavor)

  @deprecated("use `enablePlugins(AndroidApp)`", "1.7.0")
  def androidBuild: Seq[Setting[_]]= PluginRules.androidSettings

  @deprecated("Use `enablePlugins(AndroidApp)`", "1.7.0")
  def buildWith(projects: ProjectReference*): Seq[Setting[_]] = android.buildWith(projects)

  @deprecated("use `enablePlugins(AndroidJar)`", "1.7.0")
  def androidBuildJar: Seq[Setting[_]] = PluginRules.androidSettings ++ PluginRules.androidJarSettings
  @deprecated("use `enablePlugins(AndroidLib)`", "1.7.0")
  def androidBuildAar: Seq[Setting[_]] = PluginRules.androidSettings ++ PluginRules.androidAarSettings

  @deprecated("use android.useSupportVectors", "1.7.0")
  def useSupportVectors = android.useSupportVectors

}
