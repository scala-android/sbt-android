import sbt._
import sbt.Keys._

import com.android.sdklib.{IAndroidTarget,SdkManager,SdkConstants}

import java.io.File
import java.util.Properties

import scala.collection.JavaConversions._
import scala.xml.XML

import AndroidKeys._

import AndroidTasks._
import AndroidCommands._

object AndroidSdkPlugin extends Plugin {

  // android build steps
  // * handle library dependencies (android.library.reference.N)
  // * ndk TODO
  // * aidl
  // * renderscript
  // * BuildConfig.java
  // * aapt
  // * compile
  // * obfuscate
  // * dex
  // * png crunch
  // * package resources
  // * package apk
  // * sign
  // * zipalign

  lazy val androidBuildSettings: Seq[Setting[_]] = inConfig(Compile) (Seq(
    unmanagedSourceDirectories <<= setDirectories("source.dir", "src"),
    packageConfiguration in packageBin <<= ( packageConfiguration in packageBin
                                           , baseDirectory
                                           , libraryProject in Android
                                           , classesJar in Android
                                           ) map {
        (c, b, l, j) =>
        // remove R.java generated code from library projects
        val sources = if (l) {
          c.sources filter {
            case (f,n) => !f.getName.matches("R\\W+.*class")
          }
        } else {
          c.sources
        }
        new Package.Configuration(sources, j, c.options)
    },
    scalaSource       <<= setDirectory("source.dir", "src"),
    javaSource        <<= setDirectory("source.dir", "src"),
    resourceDirectory <<= baseDirectory (_ / "res"),
    unmanagedJars     <<= unmanagedJarsTaskDef,
    classDirectory    <<= (binPath in Android) (_ / "classes"),
    sourceGenerators  <+= (aaptGenerator in Android
                          , typedResourcesGenerator in Android
                          , aidl in Android
                          , buildConfigGenerator in Android
                          , renderscript in Android) map (
                            _ ++ _ ++ _ ++ _ ++ _),
    copyResources      := { Seq.empty },
    packageT          <<= packageT dependsOn(compile, pngCrunch in Android),
    javacOptions      <<= ( javacOptions
                          , platformJar in Android
                          , annotationsJar in Android) {
      (o, j, a) =>
      // users will want to call clean before compiling if changing debug
      val debugOptions = if (createDebug) Seq("-g") else Seq.empty
      // make sure javac doesn't create code that proguard won't process
      // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
      o ++ Seq("-bootclasspath" , j + File.pathSeparator + a,
        "-source", "1.5" , "-target", "1.5") ++ debugOptions
    },
    scalacOptions     <<= ( scalacOptions
                          , platformJar in Android
                          , annotationsJar in Android) map {
      (o, j, a) =>
      // scalac has -g:vars by default
      val bcp = j + File.pathSeparator + a
      o ++ Seq("-bootclasspath", bcp, "-javabootclasspath", bcp)
    }
  )) ++ inConfig(Test) (Seq(
    sourceDirectory            <<= baseDirectory (_ / "test"),
    unmanagedSourceDirectories <<= baseDirectory (b => Seq(b / "test"))
  )) ++ inConfig(Android) (Seq(
    install                 <<= installTaskDef,
    run                     <<= runTaskDef(install,
                                           sdkPath, manifest, packageName),
    packageResourcesOptions <<= packageResourcesOptionsTaskDef,
    buildConfigGenerator    <<= buildConfigGeneratorTaskDef,
    binPath                 <<= setDirectory("out.dir", "bin"),
    classesJar              <<= binPath (_ / "classes.jar"),
    classesDex              <<= binPath (_ / "classes.dex"),
    aaptNonConstantId        := true,
    aaptGeneratorOptions    <<= aaptGeneratorOptionsTaskDef,
    aaptGenerator           <<= aaptGeneratorTaskDef,
    aaptGenerator           <<= aaptGenerator dependsOn renderscript,
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
    annotationsJar           <<= sdkPath {
      import SdkConstants._
      _ + OS_SDK_TOOLS_FOLDER + FD_SUPPORT + File.separator + FN_ANNOTATIONS_JAR
    },
    dex                     <<= dexTaskDef,
    platformJar             <<= platform (
      _.getPath(IAndroidTarget.ANDROID_JAR)),
    manifestPath            <<= baseDirectory (_ / "AndroidManifest.xml"),
    properties              <<= baseDirectory (b => loadProperties(b)),
    manifest                <<= manifestPath { m => XML.loadFile(m) },
    versionCode             <<= manifest { m =>
      m.attribute(ANDROID_NS, "versionCode") map { _(0) text }},
    versionName             <<= manifest { m =>
      m.attribute(ANDROID_NS, "versionName") map { _(0) text }},
    packageName             <<= manifest { m =>
      m.attribute("package") get (0) text
    },
    targetSdkVersion        <<= manifest { m =>
      val usesSdk = (m \ "uses-sdk")
      if (usesSdk.isEmpty) -1 else
        ((usesSdk(0).attribute(ANDROID_NS, "targetSdkVersion") orElse
          usesSdk(0).attribute(ANDROID_NS, "minSdkVersion")) get (0) text) toInt
    },
    proguardLibraries       <<= annotationsJar (j => Seq(file(j))),
    proguardExcludes         := Seq.empty,
    proguardOptions          := Seq.empty,
    proguardConfig           := proguardConfigTaskDef,
    proguard                <<= proguardTaskDef,
    proguard                <<= proguard dependsOn (packageT in Compile),
    proguardInputs          <<= proguardInputsTaskDef,
    proguardScala           <<= (scalaSource in Compile) {
      s => (s ** "*.scala").get.size > 0
    },
    typedResources          <<= proguardScala,
    typedResourcesGenerator <<= typedResourcesGeneratorTaskDef,
    useProguard             <<= proguardScala,
    packageResources        <<= packageResourcesTaskDef,
    // packageResources needs to happen after all other project's crunches
    packageResources        <<= packageResources dependsOn(pngCrunch, dex),
    apkbuild                <<= apkbuildTaskDef,
    signRelease             <<= signReleaseTaskDef,
    zipalign                <<= zipalignTaskDef,
    packageT                <<= zipalign,
    setDebug                 := { _createDebug = true },
    setRelease               := { _createDebug = false },
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
    sdkManager              <<= sdkPath (p => SdkManager.createManager(p,null)),
    platform                <<= (sdkManager, properties) { (m, p) =>
      m.getTargetFromHashString(p("target"))
    }
  )) ++ Seq(
    cleanFiles    <+= binPath in Android,
    cleanFiles    <+= genPath in Android,
    exportJars     := true,
    unmanagedBase <<= baseDirectory (_ / "libs")
  ) ++ androidCommands

  lazy val androidCommands: Seq[Setting[_]] = Seq(
    commands ++= Seq(devices, device)
  )

  def device = Command(
    "device", ("device", "Select a connected android device"),
    "Select a device (when there are multiple) to apply actions to"
  )(deviceParser)(deviceAction)

  def devices = Command.command(
    "devices", "List connected android devices",
    "List all connected android devices")(devicesAction)
}

