package android
import java.io.File
import java.util.Properties

/**
  * @author pfnguyen
  */
private[android] trait TaskBase {

  def loadProperties(path: File): Properties = {
    import sbt._
    val p = new Properties
    (path * "*.properties").get.foreach(Using.fileInputStream(_)(p.load))
    p
  }
}
