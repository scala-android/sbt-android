import sbt._
import sbt.Keys._

import com.android.sdklib.{IAndroidTarget,SdkManager}
import com.android.sdklib.BuildToolInfo.PathId
import com.android.SdkConstants
import com.android.utils.StdLogger

import java.io.File
import java.util.Properties

import scala.collection.JavaConversions._
import scala.xml.XML

import AndroidKeys._

import AndroidTasks._
import AndroidCommands._

object AndroidSdkPlugin extends Plugin {

  System.setProperty("xsbt.skip.cp.lookup", "true")

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
                          , apklibs in Android
                          , aidl in Android
                          , buildConfigGenerator in Android
                          , renderscript in Android
                          , cleanForR in Android
                          ) map {
      (a, tr, apkl, aidl, bcg, rs, _) =>
      val apkls = apkl map { l =>
        ((l.path / "src") ** "*.java" get) ++
          ((l.path / "src") ** "*.scala" get)
      } flatten

      a ++ tr ++ aidl ++ bcg ++ rs ++ apkls
    },
    copyResources      := { Seq.empty },
    packageT          <<= packageT dependsOn(compile, pngCrunch in Android),
    javacOptions      <<= ( javacOptions
                          , sbtVersion
                          , platformJars in Android
                          , annotationsJar in Android) map {
      case (o, v, (j, x), a) =>
      // users will want to call clean before compiling if changing debug
      val debugOptions = if (createDebug) Seq("-g") else Seq.empty
      val bcp = (List(j, a) ++ x) mkString File.pathSeparator
      // make sure javac doesn't create code that proguard won't process
      // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
      // TODO sbt 0.12 expects javacOptions to be a Task, not Setting
      o ++ Seq("-bootclasspath" , bcp,
        "-source", "1.5" , "-target", "1.5") ++ debugOptions
    },
    scalacOptions     <<= ( scalacOptions
                          , platformJars in Android
                          , annotationsJar in Android) map {
      case (o, (j, x), a) =>
      // scalac has -g:vars by default
      val bcp = (List(j, a) ++ x) mkString File.pathSeparator
      o ++ Seq("-bootclasspath", bcp, "-javabootclasspath", bcp)
    }
  )) ++ inConfig(Test) (Seq(
    managedClasspath <++= (platform in Android) map { t =>
      (Option(t.getOptionalLibraries) map {
        _ map ( j => Attributed.blank(file(j.getJarPath)) )
      } flatten).toSeq
    },
    scalacOptions in console    := Seq.empty,
    sourceDirectory            <<= baseDirectory (_ / "test"),
    unmanagedSourceDirectories <<= baseDirectory (b => Seq(b / "test"))
  )) ++ inConfig(Android) (Seq(
    apklibs           <<= apklibsTaskDef,
    install                 <<= installTaskDef,
    uninstall               <<= uninstallTaskDef,
    run                     <<= runTaskDef(install,
                                           sdkPath, manifest, packageName),
    cleanAapt               <<= cleanAaptTaskDef,
    cleanForR               <<= (aaptGenerator
                                , cacheDirectory
                                , genPath
                                , classDirectory in Compile
                                , streams
                                ) map {
      (_, c, g, d, s) =>
      (FileFunction.cached(c / "clean-for-r",
          FilesInfo.hash, FilesInfo.exists) { in =>
        if (!in.isEmpty) {
          s.log.info("Rebuilding all classes because R.java has changed")
          IO.delete(d)
        }
        in
      })(Set(g ** "R.java" get: _*))
      Seq.empty[File]
    },
    customPackage            := None,
    packageResourcesOptions <<= packageResourcesOptionsTaskDef,
    buildConfigGenerator    <<= buildConfigGeneratorTaskDef,
    binPath                 <<= setDirectory("out.dir", "bin"),
    classesJar              <<= binPath (_ / "classes.jar"),
    classesDex              <<= binPath (_ / "classes.dex"),
    aaptNonConstantId        := true,
    aaptGeneratorOptions    <<= aaptGeneratorOptionsTaskDef,
    aaptGenerator           <<= aaptGeneratorTaskDef,
    aaptGenerator           <<= aaptGenerator dependsOn (
      renderscript, pngCrunch),
    aidl                    <<= aidlTaskDef,
    renderscript            <<= renderscriptTaskDef,
    pngCrunch               <<= pngCrunchTaskDef,
    genPath                 <<= baseDirectory (_ / "gen"),
    libraryProjects         <<= (baseDirectory, properties, apklibs) map {
      (b,p,a) =>
	  a ++ loadLibraryReferences(b, p)
    },
    libraryProject          <<= properties { p =>
      Option(p.getProperty("android.library")) map {
        _.equals("true") } getOrElse false },
      aaptPath                <<= (sdkPath,sdkManager) { (p,m) =>
      import SdkConstants._
      val tool = Option(m.getLatestBuildTool)
      tool map (_.getPath(PathId.AAPT)) getOrElse {
        p + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_AAPT
      }
    },
    dexPath                 <<= (sdkPath,sdkManager) { (p,m) =>
      import SdkConstants._
      val tool = Option(m.getLatestBuildTool)
      tool map (_.getPath(PathId.DX)) getOrElse {
        p + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_DX
      }
    },
    zipalignPath            <<= sdkPath {
      import SdkConstants._
      _ + OS_SDK_TOOLS_FOLDER + FN_ZIPALIGN
    },
    annotationsJar           <<= sdkPath {
      import SdkConstants._
      _ + OS_SDK_TOOLS_FOLDER + FD_SUPPORT + File.separator + FN_ANNOTATIONS_JAR
    },
    dexInputs               <<= dexInputsTaskDef,
    dex                     <<= dexTaskDef,
    platformJars            <<= platform { p =>
      (p.getPath(IAndroidTarget.ANDROID_JAR),
      (Option(p.getOptionalLibraries) map(_ map(_.getJarPath))).flatten.toSeq)
    },
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
    proguardConfig          <<= proguardConfigTaskDef,
    proguard                <<= proguardTaskDef,
    proguard                <<= proguard dependsOn (packageT in Compile),
    proguardInputs          <<= proguardInputsTaskDef,
    proguardScala           <<= (scalaSource in Compile) {
      s => (s ** "*.scala").get.size > 0
    },
    typedResources          <<= proguardScala,
    typedResourcesGenerator <<= typedResourcesGeneratorTaskDef,
    useProguard             <<= proguardScala,
    useSdkProguard          <<= proguardScala (!_),
    useProguardInDebug      <<= proguardScala,
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
    sdkPath                 <<= (thisProject,properties) { (p,props) =>
        (Option(props get "sdk.dir") orElse
          Option(System getenv "ANDROID_HOME")) flatMap { p =>
            val f = file(p + File.separator)
            if (f.exists && f.isDirectory)
              Some(p + File.separator)
            else
              None
          } getOrElse (
            sys.error("set ANDROID_HOME or run 'android update project -p %s'"
              format p.base))
    },
    sdkManager              <<= sdkPath { p =>
      SdkManager.createManager(p, new StdLogger(StdLogger.Level.VERBOSE))
    },
    platform                <<= (sdkManager, properties, thisProject) {
      (m, p, prj) =>
      val plat = Option(m.getTargetFromHashString(p("target")))
      plat getOrElse
        sys.error("Platform %s unknown in %s" format (p("target"), prj.base))
    }
  )) ++ Seq(
    cleanFiles        <+= binPath in Android,
    cleanFiles        <+= genPath in Android,
    exportJars         := true,
    unmanagedBase     <<= baseDirectory (_ / "libs")
  ) ++ androidCommands

  /*
  lazy val androidGradleSettings: Seq[Setting[_]] = androidBuildSettings ++
    inConfig(Compile) (Seq(
    ))
  */

  lazy val androidCommands: Seq[Setting[_]] = Seq(
    commands ++= Seq(devices, device, reboot, adbWifi)
  )

  def device = Command(
    "device", ("device", "Select a connected android device"),
    "Select a device (when there are multiple) to apply actions to"
  )(deviceParser)(deviceAction)

  def adbWifi = Command.command(
    "adb-wifi", "Enable/disable ADB-over-wifi for selected device",
    "Toggle ADB-over-wifi for the selected device"
  )(adbWifiAction)

  def reboot = Command(
    "reboot-device", ("reboot-device", "Reboot selected device"),
    "Reboot the selected device into the specified mode"
  )(rebootParser)(rebootAction)

  def devices = Command.command(
    "devices", "List connected and online android devices",
    "List all connected and online android devices")(devicesAction)
}

