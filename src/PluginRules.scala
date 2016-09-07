package android

private[android] object PluginRules {

  def androidSettings = deprecations.forwarder.androidSettings
  def androidAarSettings = deprecations.forwarder.androidAarSettings
  def androidJarSettings = deprecations.forwarder.androidJarSettings

  private[this] object deprecations {
    @deprecated("stub", "1.7.0")
    trait forwarder {
      def androidSettings = Plugin.androidBuild
      def androidAarSettings = Plugin.buildAar
      def androidJarSettings = Plugin.buildJar
    }

    object forwarder extends forwarder
  }
}