package android

import sbt._
import sbt.Keys._

object ProguardCache {
  def apply(prefixes: String*): ProguardCache = {
    if (prefixes.isEmpty) sys.error("ProguardCache prefixes may not be empty")
    val prefixesWithSlashes = prefixes map { prefix =>
      val p = prefix.replaceAll("""\.""", "/")
      if (!p.endsWith("/")) p + "/" else p
    }
    ProguardCache(prefixesWithSlashes, None, None, None, None)
  }
  def apply(prefix: String, file: File): ProguardCache =
    apply(prefix).copy(jarFile = some(file))
}
case class ProguardCache ( packagePrefixes: Seq[String]
                           , moduleOrg: Option[String]
                           , moduleName: Option[String]
                           , cross: Option[CrossVersion]
                           , jarFile: Option[File] ) {

  def %(orgOrName: String) = if (moduleOrg.isEmpty) {
    copy(moduleOrg = Some(orgOrName))
  } else {
    copy(moduleName = Some(orgOrName))
  }

  def %%(name: String) = {
    copy(moduleName = Some(name), cross = Some(CrossVersion.binary))
  }

  def <<(file: File) = copy(jarFile = Some(file))

  def matches(classFile: String) = packagePrefixes exists {
    classFile.startsWith(_) && classFile.endsWith(".class")
  }
  def matches(file: File) = jarFile exists {
    _.getCanonicalFile == file.getCanonicalFile }

  def matches(file: Attributed[File], state: State): Boolean = {
    matches(file.data) || (file.get(moduleID.key) exists (matches(_, state)))
  }

  def matches(module: ModuleID, state: State): Boolean = {
    (moduleOrg,moduleName,cross) match {
      case (Some(org),Some(art),Some(crs)) =>
        val extracted = Project.extract(state)
        val scalaVersion = extracted.get(sbt.Keys.scalaVersion)
        val scalaBinaryVersion = extracted.get(sbt.Keys.scalaBinaryVersion)
        val cv = CrossVersion(crs, scalaVersion, scalaBinaryVersion)
        val crossName = CrossVersion.applyCross(art, cv)
        org == module.organization &&
          (art == module.name || crossName == module.name)
      case (Some(org),Some(art),None) =>
        org == module.organization && art == module.name
      case (Some(org),None,None) => org == module.organization
      case _ => false
    }
  }
}

case class ProguardInputs(//injars: Seq[Attributed[File]],
                          injars: Seq[Attributed[File]],
                          libraryjars: Seq[File],
                          proguardCache: Option[File] = None)
