package android

import java.io.File

import sbt.complete.{Parser, Parsers}
import Parser._
import Parsers._
import sbt.{Def, State, TaskKey}
import sbt.Defaults.loadFromContext
import Def.Initialize

import scala.xml.{Node, XML}
import sbt.Cache.StringFormat
import sbinary.{Format, Input, Output}

/**
  * @author pfnguyen
  */
private[android] object parsers {
  val ACTION_MAIN = "android.intent.action.MAIN"
  def activityName(n: Node) = n.attribute(Resources.ANDROID_NS, "name").head.text
  def actionMainExists(n: Node) = {
    val x = for {
      a <- n \ "intent-filter" \ "action"
      nm <- a.attribute(Resources.ANDROID_NS, "name").toSeq.flatten
      m <- nm if m.text == ACTION_MAIN
    } yield a
    x.nonEmpty
  }
  def activityParser: Initialize[State => Parser[Option[String]]] =
    loadForParser2(Keys.processManifest, Keys.applicationId) { (state, mfile, appid) =>
      val parser = for {
        f   <- mfile
        pkg <- appid
      } yield {
        val manifest = XML.loadFile(f)
        val activities = manifest \\ "activity"
        val names = activities collect {
          case e if actionMainExists(e) => activityName(e)
        }
        EOF.map(_ => None) | (Space ~> opt(
          (token(StringBasic.examples(pkg + "/")) ~ token(StringBasic.examples(names:_*)))
            .map { case (a,b) => a + b }
        ))
      }
      parser getOrElse opt(Def.spaceDelimited("<activity name>").map(_.mkString(" ")))
    }

  private[android] implicit val sbinaryFileFormat: sbinary.Format[File] = new sbinary.Format[File] {
    override def writes(out: Output, value: File) = StringFormat.writes(out, value.getCanonicalPath)
    override def reads(in: Input) = sbt.file(StringFormat.reads(in))
  }
  def loadForParser2[P, T: Format, T2: Format](task: TaskKey[T], task2: TaskKey[T2])
                              (f: (State, Option[T], Option[T2]) => Parser[P]): Initialize[State => Parser[P]] =
    loadForParserI2(task, task2)(Def value f)

  def loadForParserI2[P, T : Format, T2 : Format](task: TaskKey[T], task2: TaskKey[T2])
                               (init: Initialize[(State, Option[T], Option[T2]) => Parser[P]]): Initialize[State => Parser[P]] =
    (sbt.Keys.resolvedScoped, init)((ctx, f) =>
      (s: State) => f(s, loadFromContext(task, ctx, s), loadFromContext(task2, ctx, s)))
}
