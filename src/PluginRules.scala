package android

import sbt.ProjectRef
private[android] object PluginRules {

  def androidSettings = deprecations.forwarder.androidSettings
  def androidAarSettings = deprecations.forwarder.androidAarSettings
  def androidJarSettings = deprecations.forwarder.androidJarSettings
  private[android] def buildWith(projects: Seq[ProjectRef]) = deprecations.forwarder.buildWith(projects)

  private[this] object deprecations {
    @deprecated("stub", "1.7.0")
    trait forwarder {
      def androidSettings = Plugin.androidBuild
      def androidAarSettings = Plugin.buildAar
      def androidJarSettings = Plugin.buildJar
      def buildWith(projects: Seq[ProjectRef]) = Plugin.buildWith(projects:_*)
    }

    object forwarder extends forwarder
  }
}