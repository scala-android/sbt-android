package android

import java.io.{FileInputStream, FileOutputStream}
import java.nio.ByteBuffer
import java.util.jar.JarInputStream

import sbt._

import net.orfjackal.retrolambda.{Main => RMain, Config => RConfig, Retrolambda}

/**
 * @author pfnguyen
 */
object RetrolambdaSupport {
  def isAvailable = RMain.isRunningJava8
  def process(target: File, classpath: Seq[File], st: State, prj: ProjectRef,
              s: sbt.Keys.TaskStreams): Seq[File] = synchronized {
    import collection.JavaConversions._
    val e = Project.extract(st)
    val bldr = e.runTask(Keys.builder in (prj,Keys.Android), st)._2
    val cp = (bldr.getBootClasspath map (f => f: sbt.File)) ++ classpath
    val dest = target / "retrolambda"
    val finalJar = target / "retrolambda-processed.jar"
    dest.mkdirs()
    val java8jars = classpath filter Java8Detector.apply
    s.log.debug("Java8 jars detected for retrolambda processing: " + java8jars)
    FileFunction.cached(s.cacheDirectory / "retrolambda-jars", FilesInfo.lastModified) { in =>
      in foreach (f => IO.unzip(f, dest))
      in
    }(java8jars.toSet)

    FileFunction.cached(s.cacheDirectory / ("retro-" + target.getName), FilesInfo.lastModified) { in =>
      val options = ForkOptions(
        runJVMOptions = Seq(
          "-noverify",
          "-classpath", e.currentUnit.classpath map (
            _.getAbsoluteFile) mkString java.io.File.pathSeparator
      ))

      val config = s.cacheDirectory / "retro-config.properties"
      val p = new java.util.Properties
      val out = new FileOutputStream(config)
      p.setProperty("retrolambda.inputDir", dest.getAbsolutePath)
      p.setProperty("retrolambda.classpath", cp map (
        _.getAbsolutePath) mkString java.io.File.pathSeparator)
      p.setProperty("retrolambda.includedFiles", (
        in map (_.getAbsoluteFile)) mkString java.io.File.pathSeparator)
      p.store(out, "auto-generated")
      out.close()
      val r = Fork.java(options, "android.RetroMain" :: config.getAbsolutePath :: Nil)
      IO.delete(config)
      if (r != 0) sys.error(s"Retrolambda failure: exit $r")
      in
    }((dest ** "*.class" get).toSet)

    IO.jar((PathFinder(dest) ***) pair rebase(dest, "") filter (
      _._1.getName endsWith ".class"), finalJar, new java.util.jar.Manifest)
    finalJar :: (classpath.toSet -- java8jars).toList
  }
}

object Java8Detector {
  def apply(jar: File): Boolean = {
    val jin = new JarInputStream(new FileInputStream(jar))

    try {
      val buf = Array.ofDim[Byte](8)
      Stream.continually(jin.getNextJarEntry) takeWhile (_ != null) exists { j =>
        if (j.getName.endsWith(".class")) {
          jin.read(buf)
          jin.closeEntry()
          val b = ByteBuffer.wrap(buf)
          val magic = b.getInt()
          if (magic != 0xcafebabe)
            sys.error("Invalid java class file: " + j.getName)
          val _ = b.getShort()
          val major = b.getShort()
          major >= 52 // java8
        } else false
      }
    } finally {
      jin.close()
    }
  }
}

object RetroMain {
  def main(args: Array[String]): Unit = {
    val p = new java.util.Properties
    val in = new FileInputStream(args(0))
    p.load(in)
    in.close()
    Retrolambda.run(new RConfig(p))
  }
}
