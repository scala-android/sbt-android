package android
import java.io.File

import sbt.{Configuration, Task, Def, Setting}

import scala.language.experimental.macros
import scala.util.{Failure, Success, Try}

package object dsl {
  def list[A](body: Seq[A]): List[A] = macro dsl.Macros.listImplN[A]
  def list[A](body: A): List[A]      = macro dsl.Macros.listImpl1[A]

  def inProject(project: String)(ss: Setting[_]*): Seq[Setting[_]] =
    inProject(sbt.ProjectRef(sbt.file(".").getCanonicalFile, project))(ss:_*)
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

  @deprecated("use android.useLibrary", "1.7.0")
  def useLibrary(library: String) =
    Keys.libraryRequests += ((library, true))

  @deprecated("use android.buildTools", "1.7.0")
  def buildTools(version: String) =
    Keys.buildToolsVersion := Option(version)

  private def extendVariant(key: sbt.SettingKey[Map[String,Seq[Setting[_]]]], name: String, ss: Seq[Setting[_]]) =
    key <<= key { vs =>
      val ss2 = vs(name)
      vs + ((name, ss2 ++ ss))
    }

  @deprecated("use android.extendFlavor", "1.7.0")
  def extendFlavor(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(Keys.flavors, name, ss)

  @deprecated("use android.flavor", "1.7.0")
  def flavor(name: String)(ss: Setting[_]*): Setting[_] =
    Keys.flavors += ((name, ss))

  @deprecated("use android.extendBuildType", "1.7.0")
  def extendBuildType(name: String)(ss: Setting[_]*): Setting[_] =
    extendVariant(Keys.buildTypes, name, ss)

  @deprecated("use android.buildType", "1.7.0")
  def buildType(name: String)(ss: Setting[_]*) =
    Keys.buildTypes += ((name, ss))

  @deprecated("use android.buildConfig", "1.7.0")
  def buildConfig(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    Keys.buildConfigOptions <+= value map { v => (`type`, name, v) }
  @deprecated("use android.buildConfig", "1.7.0")
  def buildConfig(`type`: String, name: String, value: String) =
    Keys.buildConfigOptions += ((`type`, name, value))

  @deprecated("use android.resValue", "1.7.0")
  def resValue(`type`: String, name: String, value: String) =
    Keys.resValues += ((`type`, name, value))
  @deprecated("use android.resValue", "1.7.0")
  def resValue(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    Keys.resValues <+= value map { v =>
      (`type`, name, v)
    }

  @deprecated("use android.signingConfig", "1.7.0")
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

  @deprecated("use android.apkExclude", "1.7.0")
  def apkExclude(name: String*) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(excludes = opts.excludes ++ name)
  }
  @deprecated("use android.apkPickFirst", "1.7.0")
  def apkPickFirst(name: String*) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(pickFirsts = opts.pickFirsts ++ name)
  }
  @deprecated("use android.apkMerge", "1.7.0")
  def apkMerge(name: String*) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(merges = opts.merges ++ name)
  }

  @deprecated("use android.manifestPlaceholder", "1.7.0")
  def manifestPlaceholder(key: String, value: String) =
    Keys.manifestPlaceholders += ((key,value))
  @deprecated("use android.manifestPlaceholder", "1.7.0")
  def manifestPlaceholder(key: String, value: Def.Initialize[Task[String]]) =
    Keys.manifestPlaceholders <+= value map { v => (key,v) }
  @deprecated("use android.apkVersionName", "1.7.0")
  def apkVersionName(name: String) = Keys.versionName := Option(name)
  @deprecated("use android.apkVersionCode", "1.7.0")
  def apkVersionCode(code: Int) = Keys.versionCode := Option(code)
  @deprecated("use android.apkVersionName", "1.7.0")
  def apkVersionName(name: Def.Initialize[Task[String]]) = Keys.versionName <<= name map Option.apply
  @deprecated("use android.apkVersionCode", "1.7.0")
  def apkVersionCode(code: Def.Initialize[Task[Int]]) = Keys.versionCode <<= code map Option.apply

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
