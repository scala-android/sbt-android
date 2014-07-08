package android
import sbt._

import org.objectweb.asm._

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.lang.reflect.{Method, InvocationHandler, Proxy}
import java.util.jar.{JarEntry, JarInputStream}

object NativeFinder {

  def natives(classDir: File): Seq[String] = {
    var current: String = null
    var nativeList = Seq.empty[String]

    val handler = new InvocationHandler {
      def invoke(self: Object, m: Method, args: Array[Object]): Object = {
        m.getName match {
          case "visit" =>
            if (args.length > 2) {
              current = args(2).toString
            }
          case "visitMethod" =>
            val access = args(0).asInstanceOf[Int]
            val name = args(1).toString
            if ((access & Opcodes.ACC_NATIVE) != 0) {
              nativeList +:= current.replaceAll("/", ".")
            }
          case _ =>
        }
        null
      }
    }
    val visitor = Proxy.newProxyInstance(getClass.getClassLoader, Seq(
      classOf[ClassVisitor]).toArray, handler).asInstanceOf[ClassVisitor]
    val readbuf = Array.ofDim[Byte](16384)
    val buf = new ByteArrayOutputStream

    (classDir ** "*.class" get) foreach { entry =>
      val in = new FileInputStream(entry)
      val r = new ClassReader(in)
      r.accept(visitor, 0)
      in.close()
    }

    nativeList.distinct
  }
}
