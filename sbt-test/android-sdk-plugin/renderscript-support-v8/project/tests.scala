import sbt._

object Tests {
  def findInArchive(archive: File)(predicate: String => Boolean): Boolean = {
    import java.io._
    import java.util.zip._
    val in = new ZipInputStream(new FileInputStream(archive))
    val found = Stream.continually(in.getNextEntry) takeWhile (
      _ != null) exists (e => predicate(e.getName))

    in.close()
    found
  }

  def listArchive(archive: File): Seq[String] = {
    import java.io._
    import java.util.zip._
    val in = new ZipInputStream(new FileInputStream(archive))
    val found = Stream.continually(in.getNextEntry) takeWhile (
      _ != null) map (_.getName) toList

    in.close()
    found
  }
}
