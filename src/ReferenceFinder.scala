package android

import sbt._

import org.objectweb.asm._
import org.objectweb.asm.signature.SignatureVisitor

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.lang.reflect.{Method, InvocationHandler, Proxy}
import java.util.jar.{JarEntry, JarInputStream}

object ReferenceFinder {

  type Visitor = ClassVisitor with MethodVisitor with FieldVisitor
    with AnnotationVisitor with SignatureVisitor

  def references(jar: File): Seq[String] = {
    var current: String = null
    var map: Map[String,Set[String]] = Map.empty

    val handler = new InvocationHandler {
      def invoke(self: Object, m: Method, args: Array[Object]): Object = {
        m.getName match {
          case "visit" =>
            if (args.length > 2) {
              current = args(2).toString
              if (!(current startsWith "scala/"))
                map += ((current, Set.empty))
            }
          case name@("visitAnnotation"          | "visitTypeInsn"     |
                     "visitFieldInsn"           | "visitEnum"         |
                     "visitFormalTypeParameter" | "visitTypeVariable" |
                     "visitClassType"           | "visitMethodInsn"   ) =>
            val dep = name + "(" + (args mkString ",") + ")"
            if ((dep indexOf ",scala/") != -1 &&
                !(current startsWith "scala/")) {
              map = map + ((current, map(current) + dep))
            }
          case _ =>
        }
        self
      }
    }
    val visitor = Proxy.newProxyInstance(getClass.getClassLoader, Seq(
      classOf[ClassVisitor],
      classOf[MethodVisitor],
      classOf[FieldVisitor],
      classOf[AnnotationVisitor],
      classOf[SignatureVisitor]).toArray, handler).asInstanceOf[Visitor]
    val readbuf = Array.ofDim[Byte](16384)
    val buf = new ByteArrayOutputStream

    val jin = new JarInputStream(new FileInputStream(jar))

    Stream.continually(jin.getNextJarEntry()) takeWhile (_ != null) foreach {
      entry =>
      if (entry.getName.endsWith(".class")) {
        buf.reset()
        Stream.continually(jin.read(readbuf)) takeWhile (
          _ != -1) foreach (buf.write(readbuf, 0, _))
        val r = new ClassReader(buf.toByteArray)
        r.accept(visitor, 0)
      }
      jin.closeEntry()
    }

    jin.close()

    (map flatMap { case (k,v) => v }).toList.sortWith(_>_).distinct
  }
}
