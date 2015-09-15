import android.Keys._
import android.BuildOutput._
import scala.xml._

TaskKey[Unit]("verify-package") <<= (applicationId in Android) map { p =>
  if (p != "com.example.app") error("wrong package: " + p)
  ()
}

TaskKey[Unit]("verify-res-values") <<= (
  projectLayout in Android, outputLayout in Android) map { (p, o) =>
  implicit val output = o
  val res = p.generatedRes / "values" / "generated.xml"
  val root = XML.loadFile(res)
  val node = root \ "string"
  if (node.isEmpty) sys.error("string node not found")
  val name = node.head.attribute("name").get.toString
  if ("test_resource" != name) sys.error(s"wrong name value: [$name] ${name.getClass}")
  val text = node.head.text
  if ("test value" != text) sys.error("wrong value: " + text)
  ()
}
