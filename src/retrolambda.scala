package android

import java.io.FileInputStream
import java.nio.ByteBuffer

import sbt._
import language.postfixOps

import net.orfjackal.retrolambda.{Main => RMain, SystemPropertiesConfig, Retrolambda}

/**
 * @author pfnguyen
 */
object RetrolambdaSupport {
  def isAvailable = RMain.isRunningJava8
  def apply(target: File, classpath: Seq[File], forkClasspath: Seq[File], bootClasspath: Seq[File],
              s: sbt.Keys.TaskStreams): Seq[File] = synchronized {
    val cp = bootClasspath ++ classpath
    val dest = target / "retrolambda"
    val finalJar = target / "retrolambda-processed.jar"
    dest.mkdirs()
    val java8jars = classpath filter Java8Detector.apply
    s.log.debug("Java8 jars detected for retrolambda processing: " + java8jars)
    FileFunction.cached(s.cacheDirectory / "retrolambda-jars", FilesInfo.lastModified) { in =>
      in foreach (f => IO.unzip(f, dest))
      in
    }(java8jars.toSet)

    if (java8jars.nonEmpty) {
      FileFunction.cached(s.cacheDirectory / ("retro-" + target.getName), FilesInfo.lastModified) { in =>
        val options = ForkOptions(
          runJVMOptions = Seq(
            "-noverify",
            "-classpath", forkClasspath map (
              _.getAbsoluteFile) mkString java.io.File.pathSeparator
          ))

        val config = s.cacheDirectory / "retro-config.properties"
        val p = new java.util.Properties
        Using.fileOutputStream(false)(config) { out =>
          p.setProperty("retrolambda.defaultMethods", "true")
          p.setProperty("retrolambda.inputDir", dest.getAbsolutePath)
          p.setProperty("retrolambda.classpath", cp map (
            _.getAbsolutePath) mkString java.io.File.pathSeparator)
          p.setProperty("retrolambda.includedFiles", (
            in map (_.getAbsoluteFile)) mkString java.io.File.pathSeparator)
          p.store(out, "auto-generated")
        }
        val r = Fork.java(options, "android.RetroMain" :: config.getAbsolutePath :: Nil)
        IO.delete(config)
        if (r != 0) PluginFail(s"Retrolambda failure: exit $r")
        in
      }((dest ** "*.class" get).toSet)
      IO.jar((PathFinder(dest) ***) pair rebase(dest, "") filter (
        _._1.getName endsWith ".class"), finalJar, new java.util.jar.Manifest)
    }

    finalJar :: (classpath.toSet -- java8jars).toList
  }
}

object Java8Detector {
  def apply(jar: File): Boolean = {
    Using.fileInputStream(jar)(Using.jarInputStream(_) { jin =>
      val buf = Array.ofDim[Byte](8)
      Iterator.continually(jin.getNextJarEntry) takeWhile (_ != null) exists { j =>
        if (j.getName.endsWith(".class")) {
          jin.read(buf)
          jin.closeEntry()
          val b = ByteBuffer.wrap(buf)
          val magic = b.getInt()
          if (magic != 0xcafebabe)
            PluginFail("Invalid java class file: " + j.getName)
          val _ = b.getShort()
          val major = b.getShort()
          major >= 52 // java8
        } else false
      }
    })
  }
}

object RetroMain {
  def main(args: Array[String]): Unit = {
    val p = new java.util.Properties
    // don't care about using because we're forked off and short-lived
    val in = new FileInputStream(args(0))
    p.load(in)
    in.close()
    Retrolambda.run(new SystemPropertiesConfig(p))
  }
}
