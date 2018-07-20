package android

import sbt.Def
import sbt.Keys._

import android.Keys._

import java.io.File

import android.databinding.tool._

object Databinding {
  def databindingGeneratorTaskDef = Def.task {
    if (databindingEnabled.value) {
      val s = streams.value
      implicit val out = outputLayout.value
      val layout = projectLayout.value

      val b = new DataBindingBuilder
      val input = new LayoutXmlProcessor.ResourceInput(
        false, layout.res, layout.generatedRes)
      val lookup = new LayoutXmlProcessor.OriginalFileLookup {
        override def getOriginalFileFor(f: File) = {
          s.log.info("Lookup: " + f)
          f
        }
      }
      val minSdk = scala.util.Try(minSdkVersion.value.toInt).toOption.getOrElse(26)
      val writer = b.createJavaFileWriter(layout.generatedSrc)
      val proc = new LayoutXmlProcessor(applicationId.value, writer, lookup)
      proc.processResources(input)
      proc.writeLayoutInfoFiles(layout.generated)
      proc.writeEmptyInfoClass()
      s.log.info("ICN: " + proc.getInfoClassFullName)
      val cv = b.getCompilerVersion
      s.log.info("CV: " + cv)
      s.log.info("BA: " + b.getBaseAdaptersVersion(cv))
      s.log.info("BL: " + b.getBaseLibraryVersion(cv))
      s.log.info("LV: " + b.getLibraryVersion(cv))
      s.log.info("create binder")
      /*
      val binder = new DataBinder(proc.getResourceBundle, false)
      s.log.info("set file writer")
      binder.setFileWriter(writer)
      s.log.info("Sealing models")
      binder.sealModels()
      s.log.info("writing binders")
      binder.writeBinders(minSdk)
      s.log.info("writing component")
      binder.writeComponent()
      s.log.info("writing base classes")
      binder.writerBaseClasses(false) // isLibrary
      */
      import sbt._
      Seq(layout.generatedSrc / (proc.getInfoClassFullName.replace(".", "/") + ".java"))
    } else {
      streams.value.log.info("Disabled")
      Seq.empty[File]
    }
  }
}
