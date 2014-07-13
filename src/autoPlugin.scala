package android

object AndroidPlugin extends sbt.AutoPlugin {

  object autoImport extends KeysContainer {
    type ProjectLayout = android.ProjectLayout

    val ProjectLayout = android.ProjectLayout
  }
}
