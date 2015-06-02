package android

import sbt._

case class ProguardInputs(injars: Seq[Attributed[File]],
                          libraryjars: Seq[File],
                          proguardCache: Option[File] = None)
