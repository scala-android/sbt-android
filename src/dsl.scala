package android
import java.io.File

import sbt.{Configuration, Task, Def, Setting}
import sbt.syntax._

import scala.language.experimental.macros
import scala.util.{Failure, Success, Try}

package object dsl {
  def list[A](body: Seq[A]): List[A] = macro dsl.Macros.listImplN[A]
  def list[A](body: A): List[A]      = macro dsl.Macros.listImpl1[A]

  def inProject(project: String)(ss: Setting[_]*): Seq[Setting[_]] =
    inProject(sbt.ProjectRef(sbt.syntax.file(".").getCanonicalFile, project))(ss:_*)
  def inProject(project: sbt.ProjectRef)(ss: Setting[_]*): Seq[Setting[_]] =
    ss map VariantSettings.fixProjectScope(project)
  private def stringFlags(key: sbt.TaskKey[Seq[String]], ss: Seq[String]) = key ++= ss
  private def stringFlags(key: sbt.TaskKey[Seq[String]], config: Configuration, ss: Seq[String]) =
    key in config ++= ss
  def javacFlags(opts: String*) = stringFlags(sbt.Keys.javacOptions, opts)
  def javacFlags(config: Configuration)(opts: String*) =
    stringFlags(sbt.Keys.javacOptions, config, opts)
  def scalacFlags(opts: String*) = stringFlags(sbt.Keys.scalacOptions, opts)
  def scalacFlags(config: Configuration)(opts: String*) =
    stringFlags(sbt.Keys.scalacOptions, config, opts)

  def useLibrary(library: String) =
    Keys.libraryRequests += ((library, true))

  def buildTools(version: String) =
    Keys.buildToolsVersion := Option(version)

  private def extendVariant(key: sbt.SettingKey[Map[String,Seq[Setting[_]]]], name: String, ss: Seq[Setting[_]]) =
    key <<= key { vs =>
      val ss2 = vs(name)
      vs + ((name, ss2 ++ ss))
    }
  def extendFlavor(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(Keys.flavors, name, ss)

  def flavor(name: String)(ss: Setting[_]*): Setting[_] =
    Keys.flavors += ((name, ss))

  def extendBuildType(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(Keys.buildTypes, name, ss)
  def buildType(name: String)(ss: Setting[_]*) =
    Keys.buildTypes += ((name, ss))

  def buildConfig(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    Keys.buildConfigOptions <+= value map { v => (`type`, name, v) }
  def buildConfig(`type`: String, name: String, value: String) =
    Keys.buildConfigOptions += ((`type`, name, value))

  def resValue(`type`: String, name: String, value: String) =
    Keys.resValues += ((`type`, name, value))
  def resValue(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    Keys.resValues <+= value map { v =>
      (`type`, name, v)
    }

  def signingConfig(keystore: File,
                    alias: String,
                    storePass: Option[String] = None,
                    keyPass: Option[String] = None,
                    singlePass: Boolean = true,
                    storeType: String = "jks") = {
    val sp = storePass orElse keyPass
    val config = if (sp.isEmpty) {
      if (singlePass)
        PromptStorepassSigningConfig(keystore, alias, storeType)
      else
        PromptPasswordsSigningConfig(keystore, alias, storeType)
    } else
      PlainSigningConfig(keystore, sp.get, alias, keyPass, storeType)

    Keys.apkSigningConfig := Some(config)
  }

  def apkExclude(name: String*) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(excludes = opts.excludes ++ name)
  }
  def apkPickFirst(name: String*) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(pickFirsts = opts.pickFirsts ++ name)
  }
  def apkMerge(name: String*) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(merges = opts.merges ++ name)
  }

  def manifestPlaceholder(key: String, value: String) =
    Keys.manifestPlaceholders += ((key,value))
  def manifestPlaceholder(key: String, value: Def.Initialize[Task[String]]) =
    Keys.manifestPlaceholders <+= value map { v => (key,v) }
  def apkVersionName(name: String) = Keys.versionName := Option(name)
  def apkVersionCode(code: Int) = Keys.versionCode := Option(code)
  def apkVersionName(name: Def.Initialize[Task[String]]) = Keys.versionName <<= name map Option.apply
  def apkVersionCode(code: Def.Initialize[Task[Int]]) = Keys.versionCode <<= code map Option.apply

  private[android] def checkVersion(tag: String, version: String): Unit = {
    Try(version.toInt) match {
      case Success(_) =>
      case Failure(_) => if (version.length > 1)
        PluginFail(tag + " must be an integer value or a single letter")
    }
  }

  def dexMainClassList(classes: String*) = Keys.dexMainClassesConfig := {
    val layout = Keys.projectLayout.value
    implicit val out = Keys.outputLayout.value
    sbt.IO.writeLines(layout.maindexlistTxt, classes)
    layout.maindexlistTxt
  }
}
package dsl {
private[android] object Macros {
  import scala.reflect.macros.Context

  def listImplN[A](c: Context)(body: c.Expr[Seq[A]])(implicit ev: c.WeakTypeTag[A]): c.Expr[List[A]] = {
    import c.universe._
    val xs = body.tree.children
    if (xs.isEmpty)
      c.Expr[List[A]](Apply(Select(body.tree, newTermName("toList")), Nil))
    else
      commonImpl(c)(body)
  }

  def listImpl1[A](c: Context)
                 (body: c.Expr[A])
                 (implicit ev: c.WeakTypeTag[A]): c.Expr[List[A]] = {
    import c.universe._
    val xs = body.tree.children
    if (xs.isEmpty)
      c.Expr[List[A]](Apply(Ident(newTermName("List")), body.tree :: Nil))
    else
      commonImpl(c)(body)
  }

  def commonImpl[A](c: Context)(body: c.Expr[_])(implicit ev: c.WeakTypeTag[A]): c.Expr[List[A]] = {
    import c.universe._
    val seqA = c.weakTypeOf[Seq[A]]
    c.Expr[List[A]](body.tree.children.reduce { (a,ch) =>
        val acc = if (a.tpe != null && a.tpe <:< ev.tpe) {
          Apply(Ident(newTermName("List")), a :: Nil)
        } else a
        if (ch.tpe <:< seqA)
          Apply(Select(acc, newTermName("$plus$plus")), List(ch))
        else if (ch.tpe <:< ev.tpe)
          Apply(Select(acc, newTermName("$colon$plus")), List(ch))
        else c.abort(ch.pos, s"Unexpected type: ${ch.tpe}, needed ${ev.tpe}")
      })
  }

}
}
