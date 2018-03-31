package android
import Keys._
import Keys.Internal._
import Tasks._
import android.BuildOutput.Converter
import com.android.sdklib.SdkVersionInfo
import sbt._
import sbt.Keys._

import scala.util.Try
/**
  * @author pfnguyen
  */
trait AndroidAppSettings extends AutoPlugin {
  override def projectSettings = inConfig(Android)(List(
    debugIncludesTests       := (projectLayout.value.testSources ** "*.scala").get.nonEmpty,
    installTimeout           := 0,
    install                  := (installTaskDef    dependsOn hasDevice).value,
    uninstall                := (uninstallTaskDef  dependsOn hasDevice).value,
    clean                    := (cleanTaskDef      dependsOn hasDevice).value,
    debug                    := (runTaskDef(true)  dependsOn install).evaluated,
    run                      := (runTaskDef(false) dependsOn install).evaluated,
    hasDevice                := {
      if (Commands.targetDevice(sdkPath.value, streams.value.log).isEmpty)
        PluginFail("no device connected")
    },
    allDevices               := false,
    dexInputs                := dexInputsTaskDef.value,
    dexAggregate             := dexAggregateTaskDef.value,
    proguardAggregate        := proguardAggregateTaskDef.value,
    apkbuildAggregate        := apkbuildAggregateTaskDef.value,
    predex                   := predexTaskDef.value,
    predexRetrolambda        := false,
    predexSkip               := localProjects.value.map(_.getJarFile),
    dex                      := dexTaskDef.value,
    dexShards                := false,
    dexLegacyMode            := {
      val minSdk = minSdkVersion.value
      val minLevel = Try(minSdk.toInt).toOption getOrElse
        SdkVersionInfo.getApiByBuildCode(minSdk, true)
      minLevel < 21
    },
    dexMaxHeap               := "1024m",
    dexInProcess             := false, // turn off, does not work properly?
    dexMaxProcessCount       := java.lang.Runtime.getRuntime.availableProcessors,
    dexMulti                 := false,
    dexMainRoots             := List(
      "activity",
      "application",
      "service",
      "receiver",
      "provider",
      "instrumentation"),
    dexMainClassesRules      := List(
      "-dontobfuscate",
      "-dontoptimize",
      "-dontpreverify",
      "-dontwarn **",
      "-dontnote **",
      "-forceprocessing",
      "-keep public class * extends android.app.backup.BackupAgent { <init>(); }",
      "-keep public class * extends java.lang.annotation.Annotation { *; }",
      "-keep class android.support.multidex.** { *; }"
    ),
    dexMainClasses           := Nil,
    dexMinimizeMain          := false,
    dexAdditionalParams      := Nil,
    dexMainClassesConfig     := (dexMainClassesConfigTaskDef dependsOn (packageT in Compile)).value,
    proguardVersion          := "5.0",
    proguardCache            := "scala" :: Nil,
    proguardLibraries        := Nil,
    proguardConfig           := proguardConfigTaskDef.value,
    proguardConfig           := (proguardConfig dependsOn packageResources).value,
    proguard                 := proguardTaskDef.value,
    proguardInputs           := proguardInputsTaskDef.value,
    proguardInputs           := (proguardInputs dependsOn (packageT in Compile)).value,
    proguardScala            := autoScalaLibrary.value,
    useProguard              := proguardScala.value,
    useProguardInDebug       := proguardScala.value,
    shrinkResources          := false,
    resourceShrinker         := resourceShrinkerTaskDef.value,
    packageResources         := (packageResourcesTaskDef dependsOn rGenerator).value,
    apkFile                  := {
      implicit val output: Converter = outputLayout.value
      projectLayout.value.integrationApkFile(name.value)
    },
    packagingOptions         := PackagingOptions(Nil, Nil, Nil),
    apkbuild                 := apkbuildTaskDef.value,
    apkbuild                 := (apkbuild dependsOn (managedResources in Compile)).value,
    apkDebugSigningConfig    := DebugSigningConfig(),
    apkSigningConfig         := properties {
      p => {
        def makeSigningConfig(alias: String, store: String, passwd: String) = {
          val c = PlainSigningConfig(file(store), passwd, alias)
          val c2 = Option(p.getProperty("key.store.type")).fold(c) { t =>
            c.copy(storeType = t)
          }
          Option(p.getProperty("key.alias.password")).fold(c2) { p =>
            c2.copy(keyPass = Some(p))
          }
        }

        for {
          a <- Option(p.getProperty("key.alias"))
          b <- Option(p.getProperty("key.store"))
          c <- Option(p.getProperty("key.store.password"))
        } yield makeSigningConfig(a, b, c)
      }
    }.value,
    signRelease              := signReleaseTaskDef.value,
    zipalign                 := zipalignTaskDef.value,
    packageT                 := zipalign.value,
    // I hope packageXXX dependsOn(setXXX) sets createDebug before package
    packageDebug             := packageT.value,
    packageDebug             := (packageDebug dependsOn setDebug).value,
    packageRelease           := packageT.value,
    packageRelease           := (packageRelease dependsOn setRelease).value,
    zipalignPath             := {
      val p  = sdkPath.value
      val m  = sdkManager.value
      val bt = buildToolInfo.value
      val s  = sLog.value

      val pathInBt = SdkLayout.zipalign(bt)

      s.debug("checking zipalign at: " + pathInBt)

      if (pathInBt.exists)
        pathInBt.getAbsolutePath
      else {
        val zipalign = SdkLayout.zipalign(p)
        if (!zipalign.exists)
          fail("zipalign not found at either %s or %s" format (
            pathInBt, zipalign))
        zipalign.getAbsolutePath
      }
    }
  )) ++ List(
    streams in update   := ((streams in update) dependsOn stableProguardConfig).value,
    libraryDependencies += Def.setting("net.sf.proguard" % "proguard-base" % proguardVersion.value % AndroidInternal.name).value,
      managedClasspath in AndroidInternal := Classpaths.managedJars(AndroidInternal, classpathTypes.value, update.value)
  )

  private[this] val stableProguardConfig = Def.taskDyn {
    val checkdir = streams.value.cacheDirectory / "proguardRuleCheck"
    val rulecheck = (checkdir * "*").get.toList.map(_.getName).sorted
    val ruleHash = Hash.toHex(Hash(proguardCache.value.mkString(";")))
    val optionHash = Hash.toHex(Hash(proguardOptions.value.mkString(";")))
    val checkfiles = List(ruleHash, optionHash).sorted

    if (rulecheck.nonEmpty && checkfiles != rulecheck && useProguardInDebug.value) Def.task {
      streams.value.log.warn("proguard rules have changed, forcing clean build")
      val _ = (clean in Compile).value
    } else Def.task {
      checkdir.mkdirs()
      IO.touch(checkdir / ruleHash)
      IO.touch(checkdir / optionHash)
    }
  }
}
