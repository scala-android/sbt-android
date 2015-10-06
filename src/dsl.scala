package android
import java.io.File

import com.android.tools.lint.detector.api.Issue
import sbt.{Configuration, Task, Def, Setting}
import Keys.Android

import scala.language.experimental.macros
import scala.util.{Failure, Success, Try}

package object dsl {
  def list[A](body: Seq[A]): List[A] = macro dsl.Macros.listImplN[A]
  def list[A](body: A): List[A]      = macro dsl.Macros.listImpl1[A]

  def javacOptions(config: Configuration)(opts: String*) =
    sbt.Keys.javacOptions in config ++= opts

  def sdkPath(path: String) = Keys.sdkPath := path
  def ndkPath(path: String) = Keys.ndkPath := Option(path)

  def libraryRequest(library: String) =
    Keys.libraryRequests += ((library, true))

  def platformTarget(target: String) =
    Keys.platformTarget := target
  def buildTools(version: String) =
    Keys.buildToolsVersion := Option(version)

  def flavor(name: String)(ss: Setting[_]*): Setting[_] =
    Keys.flavors += ((name, ss))
  def buildType(name: String)(ss: Setting[_]*) =
    Keys.buildTypes += ((name, ss))

  def enableTR(enable: Boolean) = Keys.typedResources := enable
  def trIgnore(pkg: String) =
    Keys.typedResourcesIgnores += pkg
  def buildConfigField(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    Keys.buildConfigOptions <+= value map { v => (`type`, name, v) }
  def buildConfigField(`type`: String, name: String, value: String) =
    Keys.buildConfigOptions += ((`type`, name, value))

  def shrinkResources(enable: Boolean) = Keys.shrinkResources := enable

  def resValue(`type`: String, name: String, value: String) =
    Keys.resValues += ((`type`, name, value))
  def resValue(`type`: String, name: String, value: Def.Initialize[Task[String]]) =
    Keys.resValues <+= value map { v =>
      (`type`, name, v)
    }

  def extraRes(folder: Def.Initialize[File]) = Keys.extraResDirectories <+= folder
  def extraRes(folder: File) = Keys.extraResDirectories += folder
  def extraAssets(folder: Def.Initialize[File]) = Keys.extraAssetDirectories <+= folder
  def extraAssets(folder: File) = Keys.extraAssetDirectories += folder

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

  def packagingExclude(name: String) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(excludes = opts.excludes :+ name)
  }
  def packagingPickFirst(name: String) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(pickFirsts = opts.pickFirsts :+ name)
  }
  def packagingMerge(name: String) = Keys.packagingOptions := {
    val opts = Keys.packagingOptions.value
    opts.copy(merges = opts.merges :+ name)
  }

  def applicationId(pkg: String) = Keys.applicationId := pkg
  def manifestPlaceholder(key: String, value: String) =
    Keys.manifestPlaceholders += ((key,value))
  def manifestPlaceholder(key: String, value: Def.Initialize[Task[String]]) =
    Keys.manifestPlaceholders <+= value map { v => (key,v) }
  def versionName(name: String) = Keys.versionName := Option(name)
  def versionCode(code: Int) = Keys.versionCode := Option(code)

  private[this] def checkVersion(version: String): Unit = {
    Try(version.toInt) match {
      case Success(_) =>
      case Failure(_) => if (version.length > 1)
        Plugin.fail("SDK version must be an integer value or a single letter")
    }
  }
  def targetSdkVersion(version: String) = {
    checkVersion(version)
    Keys.targetSdkVersion := version
  }
  def minSdkVersion(version: String) = {
    checkVersion(version)
    Keys.minSdkVersion := version
  }
  def mergeManifests(enable: Boolean) = Keys.mergeManifests := enable

  def rsTargetApi(version: String) = {
    checkVersion(version)
    Keys.rsTargetApi := version
  }
  def rsSupportMode(enable: Boolean) = Keys.rsSupportMode := enable
  def rsOptimLevel(level: Int) = Keys.rsOptimLevel := level

  def shardDex(enable: Boolean) = Keys.dexShards := enable
  def skipPredex(jar: File) = Keys.predexSkip += jar
  def dexMaxHeap(xmx: String) = Keys.dexMaxHeap := xmx
  def multidex(enable: Boolean) = Keys.dexMulti := enable
  def dexMainClasses(classes: Seq[String]) = Keys.dexMainClassesConfig := {
    val layout = Keys.projectLayout.value
    implicit val out = Keys.outputLayout.value
    sbt.IO.writeLines(layout.maindexlistTxt, classes)
    layout.maindexlistTxt
  }
  def dexParam(param: String) = Keys.dexAdditionalParams += param

  def proguardScala(enable: Boolean) = Keys.proguardScala := enable
  def proguardLibrary(jar: Def.Initialize[Task[File]]) = Keys.proguardLibraries <+= jar
  def proguardLibrary(jar: File) = Keys.proguardLibraries += jar
  def proguardOption(option: String) = Keys.proguardOptions += option
  def proguardCache(pkg: String) = Keys.proguardCache += pkg
  def proguardEnable(enable: Boolean) = Keys.useProguard := enable
  def proguardDebugEnable(enable: Boolean) = Keys.useProguardInDebug := enable

  def retrolambdaEnable(enable: Boolean) = Keys.retrolambdaEnabled := enable

  def enableLint(enable: Boolean) = Keys.lintEnabled := enable
  def lintDetector(issue: Issue) = Keys.lintDetectors += issue
}
package dsl {
object Macros {
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
