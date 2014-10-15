package android

import javassist.util.proxy.{MethodHandler, MethodFilter, ProxyFactory}

import sbt._

import org.objectweb.asm._

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.lang.reflect.Method

object NativeFinder {

  def natives(classDir: File): Seq[String] = {
    var current: String = null
    var nativeList = Seq.empty[String]

    val factory = new ProxyFactory()
    factory.setSuperclass(classOf[ClassVisitor])
    factory.setFilter(new MethodFilter {
      override def isHandled(p1: Method): Boolean = Seq("visit", "visitMethod").exists(p1.getName==)
    })
    val h = new MethodHandler {
      override def invoke(self: scala.Any, thisMethod: Method, proceed: Method, args: Array[AnyRef]) = {
        thisMethod.getName match {
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
    factory.create(Array(classOf[Int]), Array(262144.asInstanceOf[AnyRef]), h) match {
      case x: ClassVisitor => {
        val readbuf = Array.ofDim[Byte](16384)
        val buf = new ByteArrayOutputStream

        (classDir ** "*.class" get) foreach { entry =>
          val in = new FileInputStream(entry)
          val r = new ClassReader(in)
          r.accept(x, 0)
          in.close()
        }

        nativeList.distinct
      }
    }
  }
}
