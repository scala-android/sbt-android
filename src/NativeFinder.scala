package android

import javassist.util.proxy.{MethodHandler, MethodFilter, ProxyFactory}

import sbt._
import language.postfixOps

import org.objectweb.asm._

import java.lang.reflect.Method

object NativeFinder {

  def apply(classDir: File): Seq[String] = {
    var current: String = null
    var nativeList = Seq.empty[String]

    val factory = new ProxyFactory()
    factory.setSuperclass(classOf[ClassVisitor])
    factory.setFilter(new MethodFilter {
      override def isHandled(p1: Method): Boolean = Seq("visit", "visitMethod").contains(p1.getName)
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
            if ((access & Opcodes.ACC_NATIVE) != 0) {
              nativeList +:= current.replaceAll("/", ".")
            }
          case _ =>
        }
        null
      }
    }
    factory.create(Array(classOf[Int]), Array(Opcodes.ASM4.asInstanceOf[AnyRef]), h) match {
      case x: ClassVisitor =>
        (classDir ** "*.class" get) foreach { entry =>
          Using.fileInputStream(entry) { in =>
              try {
                val r = new ClassReader(in)
                r.accept(x, 0)
              } catch {
                case e: Exception => throw new IllegalArgumentException(s"Failed to process bytecode for `$entry`", e)
              }
          }
        }

        nativeList.distinct
    }
  }
}
