package android

import Keys._, Internal._

import sbt._
/**
  * @author pfnguyen
  */
trait AndroidLibSettings extends AutoPlugin {
  override def projectSettings = Seq(libraryProject := true) ++
    addArtifact(aarArtifact , packageAar)
}
