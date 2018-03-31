package android

import Keys._
import android.BuildOutput.Converter
import sbt._
import sbt.Keys._

import scala.xml.XML
/**
  * @author pfnguyen
  */
trait AndroidJarSettings extends AutoPlugin {

  override def projectSettings = Seq(
    manifest :=
      <manifest package="org.scala-android.placeholder">
        <application/>
      </manifest>,
    processManifest := {
      implicit val out: Converter = outputLayout.value
      val layout = projectLayout.value
      val manifestTarget = layout.processedManifest
      manifestTarget.getParentFile.mkdirs()
      XML.save(manifestTarget.getAbsolutePath, manifest.value, "utf-8")
      manifestTarget
    },
    buildConfigGenerator := Nil,
    rGenerator := Nil,
    debugIncludesTests := false,
    libraryProject := true,
    publishArtifact in (Compile,packageBin) := true,
    publishArtifact in (Compile,packageSrc) := true,
    mappings in (Compile,packageSrc) ++= {
      val dirs = (managedSourceDirectories in Compile).value

      (managedSources in Compile).value map { s =>
        val name = dirs.flatMap { d =>
          (s relativeTo d).toList
        }.headOption
        (s,name.fold(s.getName)(_.getPath))
      }
    },
    lintFlags := {
      val flags = lintFlags.value
      implicit val output: Converter = outputLayout.value
      val layout = projectLayout.value
      layout.bin.mkdirs()
      val config = layout.libraryLintConfig
      config.getParentFile.mkdirs()
      (layout.manifest relativeTo layout.base) foreach { path =>
        val lintconfig = <lint>
          <issue id="ParserError">
            <ignore path={path.getPath}/>
          </issue>
        </lint>
        scala.xml.XML.save(config.getAbsolutePath, lintconfig, "utf-8")
        flags.setDefaultConfiguration(config)
      }
      flags
    }
  )
}
