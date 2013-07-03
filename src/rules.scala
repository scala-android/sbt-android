package android
import sbt._
import sbt.Keys._

import com.android.builder.AndroidBuilder
import com.android.builder.DefaultSdkParser
import com.android.sdklib.{IAndroidTarget,SdkManager}
import com.android.sdklib.BuildToolInfo.PathId
import com.android.SdkConstants
import com.android.utils.StdLogger

import java.io.File
import java.util.Properties

import scala.collection.JavaConversions._
import scala.xml.XML

import Keys._
import Tasks._
import Commands._

object Plugin extends sbt.Plugin {

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

  lazy val androidBuild: Project.SettingsDefinition = {
    // only set the property below if this plugin is actually used
    // this property is a workaround for bootclasspath messing things
    // up and causing full-recompiles
    System.setProperty("xsbt.skip.cp.lookup", "true")
    seq(allPluginSettings:_*)
  }

  def androidBuild(projects: Project*): Project.SettingsDefinition = {
    androidBuild ++
      (projects map { p =>
        Seq(
          collectResources in Android <<=
            collectResources in Android dependsOn (compile in Compile in p),
          compile in Compile <<= compile in Compile dependsOn(
            packageT in Compile in p)
        )
      }).flatten
  }

  lazy val androidBuildAar: Project.SettingsDefinition = androidBuildAar()
  lazy val androidBuildApklib: Project.SettingsDefinition = androidBuildApklib()
  def androidBuildAar(projects: Project*): Project.SettingsDefinition = {
    androidBuild(projects:_*) ++ buildAar
  }
  def androidBuildApklib(projects: Project*): Project.SettingsDefinition = {
    androidBuild(projects:_*) ++ buildApklib
  }

  def buildAar =
      addArtifact(aarArtifact in Android, packageAar in Android)

  def buildApklib =
    addArtifact(apklibArtifact in Android, packageApklib in Android)

  private lazy val allPluginSettings: Seq[Setting[_]] = inConfig(Compile) (Seq(
    unmanagedSourceDirectories <<= (projectLayout in Android) (l =>
      Seq(l.sources)),
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
    publishArtifact in packageBin := false,
    publishArtifact in packageSrc := false,
    scalaSource       <<= (projectLayout in Android) (_.scalaSource),
    javaSource        <<= (projectLayout in Android) (_.javaSource),
    resourceDirectory <<= (projectLayout in Android) (_.res),
    unmanagedJars     <<= unmanagedJarsTaskDef,
    classDirectory    <<= (binPath in Android) (_ / "classes"),
    sourceGenerators  <+= (rGenerator in Android
                          , typedResourcesGenerator in Android
                          , apklibs in Android
                          , aidl in Android
                          , buildConfigGenerator in Android
                          , renderscript in Android
                          , cleanForR in Android
                          ) map {
      (a, tr, apkl, aidl, bcg, rs, _) =>
      val apkls = apkl map { l =>
        (l.layout.javaSource ** "*.java" get) ++
          (l.layout.scalaSource ** "*.scala" get)
      } flatten

      a ++ tr ++ aidl ++ bcg ++ rs ++ apkls
    },
    copyResources      := { Seq.empty },
    packageT          <<= packageT dependsOn(compile),
    javacOptions      <<= ( javacOptions
                          , sdkParser in Android) map {
      case (o, p) =>
      // users will want to call clean before compiling if changing debug
      val debugOptions = if (createDebug) Seq("-g") else Seq.empty
      val bcp = AndroidBuilder.getBootClasspath(p) mkString File.pathSeparator
      // make sure javac doesn't create code that proguard won't process
      // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
      // TODO sbt 0.12 expects javacOptions to be a Task, not Setting
      o ++ Seq("-bootclasspath" , bcp,
        "-source", "1.5" , "-target", "1.5") ++ debugOptions
    },
    scalacOptions     <<= ( scalacOptions
                          , sdkParser in Android) map {
      case (o, p) =>
      // scalac has -g:vars by default
      val bcp = AndroidBuilder.getBootClasspath(p) mkString File.pathSeparator
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
  )) ++ inConfig(Android) (Classpaths.configSettings ++ Seq(
    // productX := Nil is a necessity to use Classpaths.configSettings
    exportedProducts         := Nil,
    products                 := Nil,
    productDirectories       := Nil,
    classpathConfiguration   := config("compile"),
    // hack since it doesn't take in dependent project's libs
    dependencyClasspath     <<= dependencyClasspath in Compile map { cp =>
      val internals = Configurations.defaultInternal.toSet
      // do not filter out provided libs for scala, we do that later
      cp filterNot {
        a => (a.get(configuration.key) map internals getOrElse false) &&
          (a.get(moduleID.key) map (
            _.organization != "org.scala-lang") getOrElse false)
      }
    },
    // end for Classpaths.configSettings
    apklibs                 <<= apklibsTaskDef,
    aars                    <<= aarsTaskDef,
    aarArtifact             <<= name { n => Artifact(n, "aar", "aar") },
    apklibArtifact          <<= name { n => Artifact(n, "apklib", "apklib") },
    packageAar              <<= packageAarTaskDef,
    packageApklib           <<= packageApklibTaskDef,
    install                 <<= installTaskDef,
    uninstall               <<= uninstallTaskDef,
    run                     <<= runTaskDef(install,
                                           sdkPath, manifest, packageName),
    cleanForR               <<= (rGenerator
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
    packageForR              := None,
    buildConfigGenerator    <<= buildConfigGeneratorTaskDef,
    binPath                 <<= projectLayout (_.bin),
    classesJar              <<= binPath (_ / "classes.jar"),
    classesDex              <<= binPath (_ / "classes.dex"),
    rGenerator              <<= rGeneratorTaskDef,
    rGenerator              <<= rGenerator dependsOn (renderscript),
    aidl                    <<= aidlTaskDef,
    renderscript            <<= renderscriptTaskDef,
    genPath                 <<= projectLayout (_.gen),
    libraryProjects         <<= (baseDirectory, properties, apklibs, aars) map {
      (b,p,a,aa) =>
	  a ++ aa ++ loadLibraryReferences(b, p)
    },
    libraryProject          <<= properties { p =>
      Option(p.getProperty("android.library")) map {
        _.equals("true") } getOrElse false },
    zipalignPath            <<= sdkPath {
      import SdkConstants._
      _ + OS_SDK_TOOLS_FOLDER + FN_ZIPALIGN
    },
    dexInputs               <<= dexInputsTaskDef,
    dex                     <<= dexTaskDef,
    platformJars            <<= platform { p =>
      (p.getPath(IAndroidTarget.ANDROID_JAR),
      (Option(p.getOptionalLibraries) map(_ map(_.getJarPath))).flatten.toSeq)
    },
    projectLayout           <<= baseDirectory (ProjectLayout.apply),
    manifestPath            <<= projectLayout { l =>
      l.manifest
    },
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
    proguardLibraries        := Seq.empty,
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
    collectResources        <<= collectResourcesTaskDef,
    packageResources        <<= packageResourcesTaskDef,
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
    ilogger                  := new StdLogger(StdLogger.Level.WARNING),
    sdkParser               <<= (sdkManager, platformTarget, ilogger) {
      (m, t, l) =>
      val parser = new DefaultSdkParser(m.getLocation)

      parser.initParser(t, m.getLatestBuildTool.getRevision, l)
      parser
    },
    builder                 <<= (sdkParser, ilogger) {
      (parser, l) =>

      new AndroidBuilder(parser, "android-sdk-plugin", l, false)
    },
    sdkManager              <<= (sdkPath,ilogger) { (p, l) =>
      SdkManager.createManager(p, l)
    },

    platformTarget          <<= properties (_("target")),
    platform                <<= (sdkManager, platformTarget, thisProject) {
      (m, p, prj) =>
      val plat = Option(m.getTargetFromHashString(p))
      plat getOrElse sys.error("Platform %s unknown in %s" format (p, prj.base))
    }
  )) ++ Seq(
    crossPaths      <<= (scalaSource in Compile) { src =>
      (src ** "*.scala").get.size > 0
    },
    resolvers        <++= (sdkPath in Android) { p => Seq(
      "google libraries" at (
        file(p) / "extras" / "google" / "m2repository").toURI.toString,
      "android libraries" at (
        file(p) / "extras" / "android" / "m2repository").toURI.toString)
    },
    cleanFiles        <+= binPath in Android,
    cleanFiles        <+= genPath in Android,
    exportJars         := true,
    unmanagedBase     <<= (projectLayout in Android) (_.libs)
  ) ++ androidCommands

  lazy val androidCommands: Seq[Setting[_]] = Seq(
    commands ++= Seq(devices, device, reboot, adbWifi)
  )

  private def device = Command(
    "device", ("device", "Select a connected android device"),
    "Select a device (when there are multiple) to apply actions to"
  )(deviceParser)(deviceAction)

  private def adbWifi = Command.command(
    "adb-wifi", "Enable/disable ADB-over-wifi for selected device",
    "Toggle ADB-over-wifi for the selected device"
  )(adbWifiAction)

  private def reboot = Command(
    "reboot-device", ("reboot-device", "Reboot selected device"),
    "Reboot the selected device into the specified mode"
  )(rebootParser)(rebootAction)

  private def devices = Command.command(
    "devices", "List connected and online android devices",
    "List all connected and online android devices")(devicesAction)
}
