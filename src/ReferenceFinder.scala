package android

import javassist.util.proxy.{MethodHandler, MethodFilter, ProxyFactory}

import sbt._

import org.objectweb.asm._
import org.objectweb.asm.signature.SignatureVisitor

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.lang.reflect.Method
import java.util.jar.JarInputStream

object ReferenceFinder {

  def apply(jar: File, prefixes: Seq[String]): Seq[String] = {
    var current: String = null
    var map: Map[String,Set[String]] = Map.empty

    var classesMap: Map[Class[_], AnyRef] = Map.empty
    val classes = List(classOf[ClassVisitor], classOf[MethodVisitor], classOf[FieldVisitor], classOf[AnnotationVisitor], classOf[SignatureVisitor])
    val handler = new MethodHandler {
      override def invoke(self: AnyRef, thisMethod: Method, proceed: Method, args: Array[AnyRef]) = {
        thisMethod.getName match {
          case "visit" =>
            if (args.length > 2) {
              current = args(2).toString
              if (!(prefixes exists (current startsWith _)))
                map += ((current, Set.empty))
            }
          case name@("visitAnnotation"          | "visitTypeInsn"     |
                     "visitFieldInsn"           | "visitEnum"         |
                     "visitFormalTypeParameter" | "visitTypeVariable" |
                     "visitClassType"           | "visitMethodInsn"   ) =>
            val dep = name + "(" + (args mkString ",") + ")"
            if ((prefixes exists (p => (dep indexOf ("," + p)) != -1)) &&
              !(prefixes exists (current startsWith _))) {
              map = map + ((current, map(current) + dep))
            }
          case _ =>
        }
        val x = thisMethod.getReturnType
        if ( classes.exists(x==) )
          classesMap(x)
        else
          null
      }
    }
    classesMap = classes.map { clazz =>
      val factory = new ProxyFactory()
      factory.setSuperclass(clazz)
      factory.setFilter(new MethodFilter {
        override def isHandled(p1: Method): Boolean = true
      })
      val o = factory.create(Array(classOf[Int]), Array(Opcodes.ASM4.asInstanceOf[AnyRef]), handler)
      (clazz, o)
    }.toMap
    classesMap(classOf[ClassVisitor]) match {
      case x: ClassVisitor =>
        val readbuf = Array.ofDim[Byte](16384)
        val buf = new ByteArrayOutputStream

        val jin = new JarInputStream(new FileInputStream(jar))

        Stream.continually(jin.getNextJarEntry) takeWhile (_ != null) foreach {
          entry =>
            if (entry.getName.endsWith(".class")) {
              buf.reset()
              Stream.continually(jin.read(readbuf)) takeWhile (
                _ != -1) foreach (buf.write(readbuf, 0, _))
              val r = new ClassReader(buf.toByteArray)
              r.accept(x, 0)
            }
            jin.closeEntry()
        }

        jin.close()
    }

    (map flatMap { case (k,v) => v }).toList.sortWith(_>_).distinct
  }
}
