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
  override def projectSettings = inConfig(Compile)(List(
    sourceGenerators += debugTestsGenerator.taskValue
  )) ++ inConfig(Android)(List(
    installTimeout           := 0,
    install                 <<= installTaskDef,
    uninstall               <<= uninstallTaskDef,
    clean                   <<= cleanTaskDef,
    debug                   <<= runTaskDef(true) dependsOn install,
    run                     <<= runTaskDef(false) dependsOn install,
    // TODO support testing in `AndroidLib`
    allDevices               := false,
    test                    <<= testTaskDef,
    test                    <<= test dependsOn (compile in Android, install),
    testOnly                <<= testOnlyTaskDef,
    dexInputs               <<= dexInputsTaskDef,
    dexAggregate            <<= dexAggregateTaskDef,
    proguardAggregate       <<= proguardAggregateTaskDef,
    apkbuildAggregate       <<= apkbuildAggregateTaskDef,
    testAggregate           <<= testAggregateTaskDef,
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
    proguardOptions          := Nil,
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
    instrumentTestTimeout    := 180000,
    instrumentTestRunner     := "android.test.InstrumentationTestRunner",
    debugTestsGenerator     <<= (debugIncludesTests,projectLayout) map {
      (tests,layout) =>
        if (tests)
          (layout.testScalaSource ** "*.scala").get ++
            (layout.testJavaSource ** "*.java").get
        else Nil
    },
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
  )) ++ inConfig(Android)(Defaults.compileAnalysisSettings ++ List(
    // stuff to support `android:compile`
    scalacOptions               := (scalacOptions in Compile).value,
    javacOptions                := (javacOptions in Compile).value,
    manipulateBytecode          := compileIncremental.value,
    TaskKey[Option[xsbti.Reporter]]("compilerReporter") := None,
    compileIncremental         <<= Defaults.compileIncrementalTask,
    compile <<= Def.taskDyn {
      if (debugIncludesTests.value) Def.task {
        (compile in Compile).value
      } else Defaults.compileTask
    },
    compileIncSetup := {
      Compiler.IncSetup(
        Defaults.analysisMap((dependencyClasspath in AndroidTestInternal).value),
        definesClass.value,
        (skip in compile).value,
        // TODO - this is kind of a bad way to grab the cache directory for streams...
        streams.value.cacheDirectory / compileAnalysisFilename.value,
        compilerCache.value,
        incOptions.value)
    },
    compileInputs in compile := {
      val cp = classDirectory.value +: Attributed.data((dependencyClasspath in AndroidTestInternal).value)
      Compiler.inputs(cp, sources.value, classDirectory.value, scalacOptions.value, javacOptions.value, maxErrors.value, sourcePositionMappers.value, compileOrder.value)(compilers.value, compileIncSetup.value, streams.value.log)
    },
    compileAnalysisFilename := {
      // Here, if the user wants cross-scala-versioning, we also append it
      // to the analysis cache, so we keep the scala versions separated.
      val extra =
      if (crossPaths.value) s"_${scalaBinaryVersion.value}"
      else ""
      s"inc_compile$extra"
    }

  )) ++ inConfig(AndroidTest)(List(
    aars in AndroidTest <<= Tasks.androidTestAarsTaskDef,
    managedClasspath := Classpaths.managedJars(AndroidTest, classpathTypes.value, update.value),
    externalDependencyClasspath := managedClasspath.value ++
      (aars in AndroidTest).value.map(a => Attributed.blank(a.getJarFile)),
    dependencyClasspath := externalDependencyClasspath.value ++ (internalDependencyClasspath in Runtime).value
  )) ++ List(
    streams in update <<= (streams in update) dependsOn stableProguardConfig,
    libraryDependencies <+= Def.setting("net.sf.proguard" % "proguard-base" % proguardVersion.value % AndroidInternal.name),
    managedClasspath in AndroidInternal := Classpaths.managedJars(AndroidInternal, classpathTypes.value, update.value),
    dependencyClasspath in AndroidTestInternal := (dependencyClasspath in AndroidTest).value ++ (dependencyClasspath in Runtime).value
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
