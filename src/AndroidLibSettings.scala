package android

import Keys._, Internal._

import sbt._
import sbt.Keys._
/**
  * @author pfnguyen
  */
trait AndroidLibSettings extends AutoPlugin {
  override def projectSettings = Seq(
    aarArtifact            := normalizedName { n => Artifact(n, "aar", "aar") }.value,
    packageAar             := Tasks.packageAarTaskDef.value,
    mappings in packageAar := Tasks.packageAarMappings.value,
    libraryProject         := true
  ) ++ addArtifact(aarArtifact , packageAar)
}
