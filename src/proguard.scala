package android

import java.io.{FileOutputStream, FileInputStream}
import java.util.jar.{JarOutputStream, JarInputStream}

import sbt._

case class ProguardInputs(injars: Seq[Attributed[File]],
                          libraryjars: Seq[File],
                          proguardCache: Option[File] = None)

object ProguardUtil {
  // write to output jar directly, no intermediate unpacking
  def createCacheJar(proguardJar: File, outJar: File,
                     rules: Seq[String], log: Logger): Unit = {
    log.info("Creating proguard cache: " + outJar.getName)
    val jin = new JarInputStream(new FileInputStream(proguardJar))
    val jout = new JarOutputStream(new FileOutputStream(outJar))
    try {
      val buf = Array.ofDim[Byte](32768)
      Stream.continually(jin.getNextJarEntry) takeWhile (_ != null) filter { entry =>
        Tasks.inPackages(entry.getName, rules) && !entry.getName.matches(".*/R\\W+.*class")
        } foreach {
        entry =>
          jout.putNextEntry(entry)
          Stream.continually(jin.read(buf, 0, 32768)) takeWhile (_ != -1) foreach { r =>
            jout.write(buf, 0, r)
          }
      }
    } finally {
      jin.close()
      jout.close()
    }
  }
}
