package android

import sbt._

import net.orfjackal.retrolambda.{Main => RMain, Config => RConfig, Retrolambda}

/**
 * @author pfnguyen
 */
object RetrolambdaSupport {
  def isAvailable = RMain.isRunningJava8
  def process(target: File, classpath: Seq[File], st: State, s: sbt.Keys.TaskStreams): File = synchronized {
    import collection.JavaConversions._
    val e = Project.extract(st)
    val bldr = e.runTask(Keys.builder in Keys.Android, st)._2
    val cp = (bldr.getBootClasspath map (f => f: sbt.File)) ++ classpath
    val dest = target / "retrolambda"
    val finalJar = target / "retrolambda-processed.jar"
    dest.mkdirs()
    FileFunction.cached(s.cacheDirectory / "retrolambda-jars", FilesInfo.lastModified) { in =>
      in foreach (f => IO.unzip(f, dest))
      in
    }(classpath.toSet)

    FileFunction.cached(s.cacheDirectory / ("retro-" + target.getName), FilesInfo.lastModified) { in =>
      Retrolambda.run(RetrolambdaConfig(dest, cp, Some(in.toSeq)).asConfig)
      in
    }((dest ** "*.class" get).toSet)

    IO.jar((PathFinder(dest) ***) pair rebase(dest, "") filter (
      _._1.getName endsWith ".class"), finalJar, new java.util.jar.Manifest)
    finalJar
  }
}

case class RetrolambdaConfig(target: File,
                             classpath: Seq[File],
                             includes: Option[Seq[File]] = None) {
  def asConfig: RConfig = {
    val p = new java.util.Properties
    p.setProperty("retrolambda.inputDir", target.getAbsolutePath)
    p.setProperty("retrolambda.classpath", classpath map (
      _.getAbsolutePath) mkString java.io.File.pathSeparator)
    val r = rebase(target, "")
    includes foreach { i =>
      p.setProperty("retrolambda.includedFiles", (
        i map (_.getAbsoluteFile)) mkString java.io.File.pathSeparator)
      println("included files: " + p.getProperty("retrolambda.includedFiles"))
    }
    new RConfig(p)
  }
}
