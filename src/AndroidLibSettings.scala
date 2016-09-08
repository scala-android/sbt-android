package android

import Keys._, Internal._

import sbt._
import sbt.Keys._
/**
  * @author pfnguyen
  */
trait AndroidLibSettings extends AutoPlugin {
  override def projectSettings = Seq(
    aarArtifact            <<= normalizedName { n => Artifact(n, "aar", "aar") },
    packageAar             <<= Tasks.packageAarTaskDef,
    mappings in packageAar <<= Tasks.packageAarMappings,
    libraryProject          := true
  ) ++
    addArtifact(aarArtifact , packageAar)
}
