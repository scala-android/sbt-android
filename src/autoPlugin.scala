package android

import sbt._

object AndroidPlugin extends sbt.AutoPlugin {

  object autoImport extends KeysContainer {
    type ProjectLayout = android.ProjectLayout
    val ProjectLayout = android.ProjectLayout

    type ProguardCache = android.ProguardCache
    val ProguardCache = android.ProguardCache
    type ProguardInputs = android.ProguardInputs
    val ProguardInputs = android.ProguardInputs

    import Dependencies.artifacts
    def apklib(m: ModuleID, n: String): ModuleID = artifacts(m, n, "apklib")
    def apklib(m: ModuleID): ModuleID            = artifacts(m, m.name, "apklib")
    def aar(m: ModuleID, n: String): ModuleID    = artifacts(m, n, "aar")
    def aar(m: ModuleID): ModuleID               = artifacts(m, m.name, "aar")

    val AutoLibraryProject = Dependencies.AutoLibraryProject
    type AutoLibraryProject = Dependencies.AutoLibraryProject

    type LibraryProject = Dependencies.LibraryProject
    val LibraryProject = Dependencies.LibraryProject
  }
}
