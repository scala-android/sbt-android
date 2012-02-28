import sbt._
import sbt.Keys._

import com.android.sdklib.{IAndroidTarget,SdkManager,SdkConstants}

import java.io.File
import java.util.Properties

import scala.collection.JavaConversions._
import scala.xml.XML

import AndroidKeys._

import Tasks._

object AndroidSdkPlugin extends Plugin {

    // android build steps
    // * handle library dependencies (android.library.reference.N)
    // * ndk TODO
    // * aidl TODO
    // * renderscript TODO
    // * aapt
    // * compile
    // * obfuscate
    // * dex
    // * png crunch
    // * package resources
    // * package apk
    // * sign
    // * zipalign

    lazy val androidBuildSettings: Seq[Setting[_]] =
    inConfig(Compile) (Seq(
        unmanagedSourceDirectories <<= setDirectories("source.dir", "src"),
        scalaSource       <<= setDirectory("source.dir", "src"),
        javaSource        <<= setDirectory("source.dir", "src"),
        resourceDirectory <<= baseDirectory (_ / "res"),
        unmanagedJars     <<= unmanagedJarsTaskDef,
        classDirectory    <<= (binPath in Android) (_ / "classes"),
        sourceGenerators  <+= (aaptGenerator in Android,
                aidl in Android, renderscript in Android) map (_ ++ _ ++ _),
        copyResources      := { Seq.empty },
        packageConfiguration in packageBin <<=
                ( packageConfiguration in packageBin
                , baseDirectory
                , libraryProject in Android
                , classesJar in Android) map {
            (c, b, l, j) =>
            new Package.Configuration(c.sources, j, c.options)
        },
        packageT          <<= packageT dependsOn(compile,
                pngCrunch in Android),
        javacOptions      <<= (javacOptions, platformJar in Android) {
            (o, j) =>
            // users will want to call clean before compiling if changing debug
            val debugOptions = if (createDebug) Seq("-g") else Seq.empty
            // make sure javac doesn't create code that proguard won't process
            // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
            o ++ Seq("-bootclasspath", j,
                     "-source", "1.5",
                     "-target", "1.5") ++ debugOptions
        },
        scalacOptions     <<= (scalacOptions, platformJar in Android) map {
            (o, j) =>
            // scalac has -g:vars by default
            o ++ Seq("-bootclasspath", j, "-javabootclasspath", j)
        }
    )) ++ inConfig(Android) (Seq(
        packageResourcesOptions <<= packageResourcesOptionsTaskDef,
        binPath                 <<= setDirectory("out.dir", "bin"),
        classesJar              <<= binPath (_ / "classes.jar"),
        classesDex              <<= binPath (_ / "classes.dex"),
        aaptGeneratorOptions    <<= aaptGeneratorOptionsTaskDef,
        aaptGenerator           <<= aaptGeneratorTaskDef,
        aidl                    <<= aidlTaskDef,
        renderscript            <<= renderscriptTaskDef,
        pngCrunch               <<= pngCrunchTaskDef,
        genPath                 <<= baseDirectory (_ / "gen"),
        libraryProjects         <<= properties { p =>
            p.stringPropertyNames.collect {
                case k if k.startsWith("android.library.reference") => k
            }.toList.sortWith { (a,b) => a < b } map { k => p(k) }
        },
        libraryProject          <<= properties { p =>
            Option(p.getProperty("android.library")) map {
                    _.equals("true") } getOrElse false },
        aaptPath                <<= sdkPath {
            import SdkConstants._
            _ + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_AAPT
        },
        dexPath                 <<= sdkPath {
            import SdkConstants._
            _ + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_DX
        },
        zipalignPath            <<= sdkPath {
            import SdkConstants._
            _ + OS_SDK_TOOLS_FOLDER + FN_ZIPALIGN
        },
        dex                     <<= dexTaskDef,
        platformJar             <<= platform (
                _.getPath(IAndroidTarget.ANDROID_JAR)),
        manifestPath            <<= baseDirectory (_ / "AndroidManifest.xml"),
        properties              <<= baseDirectory (b => loadProperties(b)),
        manifest                <<= manifestPath { m => XML.loadFile(m) },
        versionCode             <<= manifest { m =>
            val ns = m.getNamespace("android")
            m.attribute(ns, "versionCode") map { _(0) text }},
        versionName             <<= manifest { m =>
            val ns = m.getNamespace("android")
            m.attribute(ns, "versionName") map { _(0) text }},
        packageName             <<= manifest { m =>
            m.attribute("package") get (0) text
        },
        proguardLibraries        := Seq.empty,
        proguardExcludes         := Seq.empty,
        proguardOptions          := Seq.empty,
        proguardConfig           := proguardConfigDef,
        proguard                <<= proguardTaskDef,
        proguard                <<= proguard dependsOn (packageT in Compile),
        proguardInputs          <<= proguardInputsTaskDef,
        proguardScala           <<= (scalaSource in Compile) {
            s => (s ** "*.scala").get.size > 0
        },
        useProguard             <<= proguardScala,
        packageResources        <<= packageResourcesTaskDef,
        // packageResources needs to happen after all other project's crunches
        packageResources        <<= packageResources dependsOn(pngCrunch, dex),
        apkbuild                <<= apkbuildTaskDef,
        signRelease             <<= signReleaseTaskDef,
        zipalign                <<= zipalignTaskDef,
        packageT                <<= zipalign,
        setDebug                 := { createDebug = true },
        setRelease               := { createDebug = false },
        // I hope packageXXX dependsOn(setXXX) sets createDebug before package
        // because of how packageXXX is implemented by using task.?
        packageDebug            <<= packageT.task.? {
            _ getOrElse sys.error("package failed")
        },
        packageDebug            <<= packageDebug dependsOn(setDebug),
        packageRelease          <<= packageT.task.? {
            _ getOrElse sys.error("package failed")
        },
        packageRelease          <<= packageRelease dependsOn(setRelease),
        sdkPath                 <<= properties (_("sdk.dir") + File.separator),
        sdkManager              <<= sdkPath (p =>
            SdkManager.createManager(p, null)),
        platform                <<= (sdkManager, properties) { (m, p) =>
            m.getTargetFromHashString(p("target"))
        }
    )) ++ Seq(
        cleanFiles    <+= binPath in Android,
        cleanFiles    <+= genPath in Android,
        unmanagedBase <<= baseDirectory (_ / "libs")
    )
}

