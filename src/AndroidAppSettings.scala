package android
import Keys._
import Keys.Internal._
import Tasks._
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
    install                 <<= installTaskDef,
    uninstall               <<= uninstallTaskDef,
    clean                   <<= cleanTaskDef,
    debug                   <<= runTaskDef(true) dependsOn install,
    run                     <<= runTaskDef(false) dependsOn install,
    allDevices               := false,
    dexInputs               <<= dexInputsTaskDef,
    dexAggregate            <<= dexAggregateTaskDef,
    proguardAggregate       <<= proguardAggregateTaskDef,
    apkbuildAggregate       <<= apkbuildAggregateTaskDef,
    predex                  <<= predexTaskDef,
    predexRetrolambda        := false,
    predexSkip               := {
      localProjects.value map (_.getJarFile)
    },
    dex                     <<= dexTaskDef,
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
    dexMainClassesConfig    <<= dexMainClassesConfigTaskDef dependsOn (packageT in Compile),
    proguardVersion          := "5.0",
    proguardCache            := "scala" :: Nil,
    proguardLibraries        := Nil,
    proguardConfig          <<= proguardConfigTaskDef,
    proguardConfig          <<= proguardConfig dependsOn packageResources,
    proguard                <<= proguardTaskDef,
    proguardInputs          <<= proguardInputsTaskDef,
    proguardInputs          <<= proguardInputs dependsOn (packageT in Compile),
    proguardScala           <<= autoScalaLibrary,
    useProguard             <<= proguardScala,
    useProguardInDebug      <<= proguardScala,
    shrinkResources          := false,
    resourceShrinker        <<= resourceShrinkerTaskDef,
    packageResources        <<= packageResourcesTaskDef dependsOn rGenerator,
    apkFile                  := {
      implicit val output = outputLayout.value
      projectLayout.value.integrationApkFile(name.value)
    },
    packagingOptions         := PackagingOptions(Nil, Nil, Nil),
    apkbuild                <<= apkbuildTaskDef,
    apkbuild                <<= apkbuild dependsOn (managedResources in Compile),
    apkDebugSigningConfig    := DebugSigningConfig(),
    apkSigningConfig        <<= properties { p =>
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
      } yield makeSigningConfig(a,b,c)
    },
    signRelease             <<= signReleaseTaskDef,
    zipalign                <<= zipalignTaskDef,
    packageT                <<= zipalign,
    // I hope packageXXX dependsOn(setXXX) sets createDebug before package
    packageDebug            <<= packageT,
    packageDebug            <<= packageDebug dependsOn setDebug,
    packageRelease          <<= packageT,
    packageRelease          <<= packageRelease dependsOn setRelease,
    zipalignPath            <<= ( sdkPath
      , sdkManager
      , buildToolInfo
      , sLog) { (p, m, bt, s) =>
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
    streams in update <<= (streams in update) dependsOn stableProguardConfig,
    libraryDependencies <+= Def.setting("net.sf.proguard" % "proguard-base" % proguardVersion.value % AndroidInternal.name),
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
