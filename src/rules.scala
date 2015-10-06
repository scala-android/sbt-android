package android

import java.util.Properties

import android.Dependencies.LibraryProject
import com.android.builder.model.SyncIssue
import com.android.ide.common.process.BaseProcessOutputHandler.BaseProcessOutput
import com.android.ide.common.process._
import com.android.tools.lint.LintCliFlags
import com.hanhuy.sbt.bintray.UpdateChecker
import sbt._
import sbt.Keys._

import com.android.builder.core.{LibraryRequest, EvaluationErrorReporter, AndroidBuilder}
import com.android.builder.sdk.DefaultSdkLoader
import com.android.sdklib.{SdkVersionInfo, AndroidTargetHash, IAndroidTarget, SdkManager}
import com.android.sdklib.repository.FullRevision
import com.android.SdkConstants
import com.android.utils.ILogger

import java.io.{PrintWriter, File}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.xml.XML
import language.postfixOps
// because of JavaProcessExecutor
import language.existentials

import Keys._
import Keys.Internal._
import Tasks._
import Commands._
import BuildOutput._

object Plugin extends sbt.Plugin {

  // android build steps
  // * handle library dependencies (android.library.reference.N)
  // * ndk
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

  /**
   * create a new project flavor, build outputs will go in "id/android"
   * does not work in conjunction with AutoBuild, must use standard build.
   */
  def flavorOf(p: Project, id: String, settings: Setting[_]*): Project = {
    val base = p.base / id
    p.copy(id = id, base = base).settings(Seq(
      (projectLayout in Android) := ProjectLayout(p.base.getCanonicalFile, Some(base.getCanonicalFile)),
      sbt.Keys.target := base) ++ settings:_*)
  }
  def withVariant(project: String,
                  buildType: Option[String] = None,
                  flavor: Option[String] = None): Setting[_] =
    sbt.Keys.onLoad in Global := (sbt.Keys.onLoad in Global).value andThen { s =>
      val ref = ProjectRef(Project.extract(s).structure.root, project)
      android.VariantSettings.withVariant(s) { variants =>
        if (!variants.status.contains(ref))
          android.VariantSettings.setVariant(s, ref, buildType, flavor)
        else s
      }
    }

  def withVariant(p: ProjectReference,
                  buildType: Option[String],
                  flavor: Option[String]): Setting[_] = withVariant(
    p match {
      case ProjectRef(_, id) => id
      case LocalProject(id)  => id
      case _ => Plugin.fail("withVariant: Unsupported ProjectReference: " + p)
    },
    buildType, flavor)


  lazy val androidBuild: Seq[Setting[_]]= {
    // only set the property below if this plugin is actually used
    // this property is a workaround for bootclasspath messing things
    // up and causing full-recompiles
    System.setProperty("xsbt.skip.cp.lookup", "true")
    allPluginSettings
  }

  @deprecated("Use Project.androidBuildWith(subprojects) instead", "1.3.3")
  def androidBuild(projects: ProjectReference*): Seq[Setting[_]]=
    androidBuild ++ buildWith(projects: _*)

  def buildWith(projects: ProjectReference*): Seq[Setting[_]] = {
    projects flatMap { p =>
      Seq(
        transitiveAars in Android <++= aars in Android in p,
        collectResources in Android <<=
          collectResources in Android dependsOn (compile in Compile in p),
        compile in Compile <<= compile in Compile dependsOn(
          packageT in Compile in p),
        localProjects in Android += LibraryProject((projectLayout in Android in p).value),
        localProjects in Android <++= localProjects in Android in p
      )
    }
  }

  lazy val androidBuildJar: Seq[Setting[_]] = androidBuild ++ buildJar

  lazy val androidBuildAar: Seq[Setting[_]] = androidBuildAar()
  @deprecated("Use aar files instead", "gradle compatibility")
  lazy val androidBuildApklib: Seq[Setting[_]] = androidBuildApklib()
  def androidBuildAar(projects: ProjectReference*): Seq[Setting[_]] = {
    androidBuild(projects:_*) ++ buildAar
  }
  @deprecated("Use aar files instead", "gradle compatibility")
  def androidBuildApklib(projects: ProjectReference*): Seq[Setting[_]] = {
    androidBuild(projects:_*) ++ buildApklib
  }

  def buildJar = Seq(
    manifest in Android := <manifest package="com.hanhuy.sbt.placeholder">
      <application/>
    </manifest>,
    buildConfigGenerator in Android := Nil,
    rGenerator in Android := Nil,
    debugIncludesTests in Android := false,
    libraryProject in Android := true,
    publishArtifact in (Compile,packageBin) := true,
    publishArtifact in (Compile,packageSrc) := true,
    mappings in (Compile,packageSrc) := (managedSources in Compile).value map (s => (s,s.getName)),
    lintFlags in Android := {
      val flags = (lintFlags in Android).value
      implicit val output = (outputLayout in Android).value
      val layout = (projectLayout in Android).value
      layout.bin.mkdirs()
      val config = layout.libraryLintConfig
      config.getParentFile.mkdirs()
      (layout.manifest relativeTo layout.base) foreach { path =>
        val lintconfig = <lint>
          <issue id="ParserError">
            <ignore path={path.getPath}/>
          </issue>
        </lint>
        scala.xml.XML.save(config.getAbsolutePath, lintconfig, "utf-8")
        flags.setDefaultConfiguration(config)
      }
      flags
    }
  )
  def buildAar = Seq(libraryProject in Android := true) ++
      addArtifact(aarArtifact in Android, packageAar in Android)

  def buildApklib = Seq(libraryProject in Android := true) ++
    addArtifact(apklibArtifact in Android, packageApklib in Android)

  private lazy val allPluginSettings: Seq[Setting[_]] = inConfig(Compile) (Seq(
    compile <<= ( compile
                , lintDetectors in Android
                , lintFlags in Android
                , lintEnabled in Android
                , lintStrict in Android
                , projectLayout in Android
                , outputLayout in Android
                , minSdkVersion in Android
                , targetSdkVersion in Android
                , streams) map { (c, ld, f, en, strict, layout, o, minSdk, tgtSdk, s) =>
      implicit val output = o
      if (en)
        AndroidLint(layout, f, ld, strict, minSdk, tgtSdk, s)
      c
    },
    sourceManaged              <<= (projectLayout in Android) (_.gen),
    unmanagedSourceDirectories <<= (projectLayout in Android) (l =>
      Set(l.sources, l.javaSource, l.scalaSource).toSeq),
    // was necessary prior to 0.13.8 to squelch "No main class detected" warning
    //packageOptions in packageBin := Package.JarManifest(new java.util.jar.Manifest) :: Nil,
    packageConfiguration in packageBin <<= ( packageConfiguration in packageBin
                                           , baseDirectory
                                           , libraryProject in Android
                                           , projectLayout in Android
                                           , outputLayout in Android
                                           ) map {
        (c, b, l, p, o) =>
        // remove R.java generated code from library projects
        implicit val output = o
        val sources = if (l) {
          c.sources filter {
            case (f,n) => !f.getName.matches("R\\W+.*class")
          }
        } else {
          c.sources
        }
        new Package.Configuration(sources, p.classesJar, c.options)
    },
    publishArtifact in packageBin := false,
    resourceDirectory <<= (projectLayout in Android) (_.resources),
    scalaSource       <<= (projectLayout in Android) (_.scalaSource),
    javaSource        <<= (projectLayout in Android) (_.javaSource),
    unmanagedJars     <<= unmanagedJarsTaskDef,
    // doesn't work properly yet, not for intellij integration
    //managedClasspath  <<= managedClasspathTaskDef,
    unmanagedClasspath <+= classDirectory map Attributed.blank,
    classDirectory     := {
      implicit val output = (outputLayout in Android).value
      (projectLayout in Android).value.classes
    },
    sourceGenerators   := sourceGenerators.value ++ List(
      (rGenerator in Android).taskValue,
      (typedResourcesGenerator in Android).taskValue,
      (aidl in Android).taskValue,
      (buildConfigGenerator in Android).taskValue,
      (renderscript in Android).taskValue,
      (debugTestsGenerator in Android).taskValue,
      (cleanForR in Android).taskValue,
      Def.task {
        ((apklibs in Android).value ++ (autolibs in Android).value) flatMap { l =>
          (l.layout.javaSource ** "*.java" get) ++
            (l.layout.scalaSource ** "*.scala" get)
        } map (_.getAbsoluteFile)
      }.taskValue
    ),
    copyResources      := { Seq.empty },
    packageT          <<= packageT dependsOn compile,
    javacOptions      <<= ( javacOptions
                          , builder in Android
                          , apkbuildDebug in Android
                          , retrolambdaEnabled in Android) map {
      (o,bldr, debug, re) =>
      // users will want to call clean before compiling if changing debug
      val debugOptions = if (debug()) Seq("-g") else Seq.empty
      val bcp = bldr.getBootClasspath.asScala mkString File.pathSeparator
      // make sure javac doesn't create code that proguard won't process
      // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
      o ++ (if (!re) Seq("-bootclasspath" , bcp) else
        Seq("-Xbootclasspath/a:" + bcp)) ++ debugOptions
    },
    javacOptions in doc := {
      (javacOptions in doc).value.flatMap { opt =>
        if (opt.startsWith("-Xbootclasspath/a:"))
          Seq("-bootclasspath", opt.substring(opt.indexOf(":") + 1))
        else if (opt == "-g")
          Seq.empty
        else Seq(opt)
      }.foldRight(List.empty[String]) {
        (x, a) => if (x != "-target") x :: a else a.drop(1)
      }
    },
    scalacOptions     <<= (scalacOptions, builder in Android) map { (o,bldr) =>
      // scalac has -g:vars by default
      val bcp = bldr.getBootClasspath.asScala mkString File.pathSeparator
      o ++ Seq("-bootclasspath", bcp, "-javabootclasspath", bcp)
    }
  )) ++ inConfig(Test) (Seq(
    exportJars         := false,
    managedClasspath <++= (platform in Android) map { t =>
      t.getOptionalLibraries.asScala map { l =>
        Attributed.blank(l.getJar)
      }
    },
    scalacOptions in console    := Seq.empty
  )) ++ inConfig(Android) (Classpaths.configSettings ++ Seq(
    flavors                     := Map.empty,
    buildTypes                  := Map.empty,
    lint                        := {
      implicit val output = outputLayout.value
      AndroidLint(projectLayout.value,
        lintFlags.value, lintDetectors.value, lintStrict.value,
        minSdkVersion.value, targetSdkVersion.value, streams.value)
    },
    lintFlags                := {
      val flags = new LintCliFlags
      flags.setQuiet(true)
      flags
    },
    lintStrict                  := false,
    lintEnabled                 := true,
    lintDetectors               := AndroidLint.lintDetectorList,
    // support for android:test
    classDirectory              := (classDirectory in Test).value,
    sourceDirectory            <<= (projectLayout in Android) (_.testSources),
    managedSources              := Nil,
    unmanagedSourceDirectories <<= (projectLayout in Android) (l =>
      Set(l.testSources, l.testJavaSource, l.testScalaSource).toSeq),
    unmanagedSources           <<= Defaults.collectFiles(
      unmanagedSourceDirectories,
      includeFilter in (Compile,unmanagedSources),
      excludeFilter in (Compile,unmanagedSources)),
    scalacOptions               := (scalacOptions in Compile).value,
    javacOptions                := (javacOptions in Compile).value,
    compile := {
      def exported(w: PrintWriter, command: String): Seq[String] => Unit =
        args => w.println((command +: args).mkString(" "))
      val s = streams.value
      val ci = (compileInputs in compile).value
      val reporter = (TaskKey[Option[xsbti.Reporter]]("compilerReporter") in (Compile,compile)).value
      lazy val x = s.text(CommandStrings.ExportStream)
      def onArgs(cs: Compiler.Compilers) =
        cs.copy(scalac = cs.scalac.onArgs(exported(x, "scalac")),
          javac = cs.javac.onArgs(exported(x, "javac")))
      val i = ci.copy(compilers = onArgs(ci.compilers))

      try reporter match {
        case Some(r) => Compiler(i, s.log, r)
        case None           => Compiler(i, s.log)
      }
      finally x.close() // workaround for #937
    },
    compileIncSetup := {
      Compiler.IncSetup(
        Defaults.analysisMap((dependencyClasspath in Test).value),
        definesClass.value,
        (skip in compile).value,
        // TODO - this is kind of a bad way to grab the cache directory for streams...
        streams.value.cacheDirectory / compileAnalysisFilename.value,
        compilerCache.value,
        incOptions.value)
    },
    compileInputs in compile := {
      val cp = classDirectory.value +: Attributed.data((dependencyClasspath in Test).value)
      Compiler.inputs(cp, sources.value, classDirectory.value, scalacOptions.value, javacOptions.value, maxErrors.value, sourcePositionMappers.value, compileOrder.value)(compilers.value, compileIncSetup.value, streams.value.log)
    },
    compileAnalysisFilename := {
      // Here, if the user wants cross-scala-versioning, we also append it
      // to the analysis cache, so we keep the scala versions separated.
      val extra =
        if (crossPaths.value) s"_${scalaBinaryVersion.value}"
        else ""
      s"inc_compile$extra"
    },
    sources <<= Classpaths.concat(unmanagedSources, managedSources),
      // productX := Nil is a necessity to use Classpaths.configSettings
    exportedProducts         := Nil,
    products                 := Nil,
    classpathConfiguration   := config("compile"),
    // hack since it doesn't take in dependent project's libs
    dependencyClasspath     <<= ( dependencyClasspath in Compile
                                , libraryDependencies
                                , streams) map { (cp, d, s) =>
      s.log.debug("Filtering compile:dependency-classpath from: " + cp)
      val pvd = d filter { dep => dep.configurations exists (_ == "provided") }

      cp foreach { a =>
        s.log.debug("%s => %s: %s" format (a.data.getName,
          a.get(configuration.key), a.get(moduleID.key)))
      }
      // it seems internal-dependency-classpath already filters out "provided"
      // from other projects, now, just filter out our own "provided" lib deps
      // do not filter out provided libs for scala, we do that later
      cp filterNot { _.get(moduleID.key) exists { m =>
          m.organization != "org.scala-lang" &&
            (pvd exists (p => m.organization == p.organization &&
              m.name == p.name))
        }
      } groupBy(_.data) map { case (k,v) => v.head } toList
    },
    // end for Classpaths.configSettings
    updateCheck              := {
      val log = streams.value.log
      UpdateChecker("pfn", "sbt-plugins", "android-sdk-plugin") {
        case Left(t) =>
          log.debug("Failed to load version info: " + t)
        case Right((versions, current)) =>
          log.debug("available versions: " + versions)
          log.debug("current version: " + BuildInfo.version)
          log.debug("latest version: " + current)
          if (versions(BuildInfo.version)) {
            if (BuildInfo.version != current) {
              log.warn(
                s"UPDATE: A newer android-sdk-plugin is available:" +
                  s" $current, currently running: ${BuildInfo.version}")
            }
          }
      }
    },
    antLayoutDetector        := {
      val log = streams.value.log
      val prj = thisProjectRef.value.project
      projectLayout.value match {
        case a: ProjectLayout.Ant if a.manifest.exists =>
          log.warn(s"Detected an ant-style project layout in $prj;")
          log.warn("  this format has been deprecated in favor of modern layouts")
          log.warn("  If this is what you want, set 'antLayoutDetector in Android := ()'")
        case _ =>
      }
    },
    transitiveAndroidLibs    := true,
    transitiveAndroidWarning := true,
    autolibs                <<= autolibsTaskDef,
    apklibs                 <<= apklibsTaskDef,
    localAars                := Nil,
    aars                    <<= aarsTaskDef,
    transitiveAars           := Nil,
    aarArtifact             <<= normalizedName { n => Artifact(n, "aar", "aar") },
    apklibArtifact          <<= normalizedName { n => Artifact(n, "apklib", "apklib") },
    packageAar              <<= packageAarTaskDef,
    mappings in packageAar  <<= packageAarMappings,
    packageApklib           <<= packageApklibTaskDef,
    mappings in packageApklib <<= packageApklibMappings,
    allDevices               := false,
    install                 <<= installTaskDef,
    uninstall               <<= uninstallTaskDef,
    clean                   <<= cleanTaskDef,
    test                    <<= testTaskDef,
    test                    <<= test dependsOn (compile in Android, install),
    testOnly                <<= testOnlyTaskDef,
    debug                   <<= runTaskDef(true) dependsOn install,
    run                     <<= runTaskDef(false) dependsOn install,
    cleanForR               <<= (rGenerator
                                , projectLayout
                                , outputLayout
                                , classDirectory in Compile
                                , streams
                                ) map {
      (_, l, o, d, s) =>
      implicit val output = o
      FileFunction.cached(s.cacheDirectory / "clean-for-r",
          FilesInfo.hash, FilesInfo.exists) { in =>
        if (in.nonEmpty) {
          s.log.info("Rebuilding all classes because R.java has changed")
          IO.delete(d)
        }
        in
      }(Set(l.generatedSrc ** "R.java" get: _*))
      Seq.empty[File]
    },
    buildConfigGenerator    <<= buildConfigGeneratorTaskDef,
    buildConfigOptions       := Nil,
    resValues                := Nil,
    resValuesGenerator      <<= resValuesGeneratorTaskDef,
    rGenerator              <<= rGeneratorTaskDef,
    rGenerator              <<= rGenerator dependsOn renderscript,
    ndkJavah                <<= ndkJavahTaskDef,
    ndkBuild                <<= ndkBuildTaskDef,
    aidl                    <<= aidlTaskDef,
    rsTargetApi             <<= (properties, minSdkVersion) map { (p, m) =>
      Option(p.getProperty("renderscript.target")).getOrElse(m) 
    },
    rsSupportMode           <<= properties { p => 
      Try(p.getProperty("renderscript.support.mode").toBoolean).getOrElse(false) 
    },
    rsOptimLevel            := 3,
    renderscript            <<= renderscriptTaskDef,
    localProjects           <<= (baseDirectory, properties) { (b,p) =>
      loadLibraryReferences(b, p)
    },
    libraryProjects          := localProjects.value ++ apklibs.value ++ aars.value,
    libraryProject          <<= properties { p =>
      Option(p.getProperty("android.library")) exists { _.equals("true") } },
    dexInputs               <<= dexInputsTaskDef,
    dexAggregate            <<= dexAggregateTaskDef,
    manifestAggregate       <<= manifestAggregateTaskDef,
    proguardAggregate       <<= proguardAggregateTaskDef,
    apkbuildAggregate       <<= apkbuildAggregateTaskDef,
    retrolambdaAggregate    <<= retrolambdaAggregateTaskDef,
    testAggregate           <<= testAggregateTaskDef,
    predex                  <<= predexTaskDef,
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
    dexMulti                 := false,
    dexMainClasses           := Seq.empty,
    dexMinimizeMain          := false,
    dexAdditionalParams      := Seq.empty,
    dexMainClassesConfig    <<= dexMainClassesConfigTaskDef dependsOn (packageT in Compile),
    platformJars            <<= platform map { p =>
      (p.getPath(IAndroidTarget.ANDROID_JAR),
      p.getOptionalLibraries.asScala map (_.getJar.getAbsolutePath))
    },
    projectLayout            := ProjectLayout(baseDirectory.value, Some(target.value)),
    outputLayout             := { layout => new BuildOutput.AndroidOutput(layout) },
    manifestPath            <<= projectLayout { l =>
      l.manifest
    },
    properties              <<= projectLayout (l => loadProperties(l.base)),
    mergeManifests           := true,
    manifestPlaceholders     := Map.empty,
    processManifest         <<= processManifestTaskDef,
    manifest                <<= manifestPath map { m =>
      if (!m.exists)
        fail("cannot find AndroidManifest.xml: " + m)
      XML.loadFile(m)
    },
    versionCode              := None,
    versionName              := None,
    packageForR             <<= manifest map { m =>
      m.attribute("package") get 0 text
    },
    applicationId            := {
      packageName.?.value.fold(manifest.value.attribute("package").head.text) { p =>
        streams.value.log.warn(
          "'packageName in Android' is deprecated, use 'applicationId in Android'")
        p
      }
    },
    targetSdkVersion        <<= (manifest, minSdkVersion) map { (m, min) =>
      val usesSdk = m \ "uses-sdk"
      if (usesSdk.isEmpty) min else
        usesSdk(0).attribute(ANDROID_NS, "targetSdkVersion").fold(min) { _.head.text }
    },
    minSdkVersion            := {
      val m = manifest.value
      val t = platformTarget.value
      val ldr = sdkLoader.value
      val tgt = ldr.getTargetInfo(t, buildTools.value.getRevision, ilogger.value(streams.value.log))
      val min = tgt.getTarget.getVersion.getApiLevel.toString

      val usesSdk = m \ "uses-sdk"
      if (usesSdk.isEmpty) min else
        usesSdk(0).attribute(ANDROID_NS, "minSdkVersion").fold(min) { _.head.text }
    },
    proguardCache            := "scala" :: Nil,
    proguardLibraries        := Seq.empty,
    proguardOptions          := Seq.empty,
    proguardConfig          <<= proguardConfigTaskDef,
    proguardConfig          <<= proguardConfig dependsOn packageResources,
    proguard                <<= proguardTaskDef,
    proguardInputs          <<= proguardInputsTaskDef,
    proguardInputs          <<= proguardInputs dependsOn (packageT in Compile),
    proguardScala           <<= autoScalaLibrary,
    retrolambdaEnabled       := false,
    typedResources          <<= autoScalaLibrary,
    typedResourcesIgnores    := Seq.empty,
    typedResourcesGenerator <<= typedResourcesGeneratorTaskDef,
    useProguard             <<= useProguardInDebug,
    useSdkProguard          <<= proguardScala (!_),
    useProguardInDebug      <<= proguardScala,
    extraResDirectories         := Nil,
    extraAssetDirectories       := Nil,
    collectResources        <<= collectResourcesTaskDef,
    collectResources        <<= collectResources dependsOn renderscript,
    collectResources        <<= collectResources dependsOn resValuesGenerator,
    shrinkResources          := false,
    resourceShrinker        <<= resourceShrinkerTaskDef,
    packageResources        <<= packageResourcesTaskDef,
    apkFile                  := {
      implicit val output = outputLayout.value
      projectLayout.value.integrationApkFile(name.value)
    },
    collectProjectJni       <<= collectProjectJniTaskDef,
    collectProjectJni       <<= collectProjectJni dependsOn renderscript,
    collectJni              <<= collectJniTaskDef,
    packagingOptions         := PackagingOptions(Nil, Nil, Nil),
    apkbuildDebug            := MutableSetting(true),
    apkbuild                <<= apkbuildTaskDef,
    apkbuild                <<= apkbuild dependsOn (managedResources in Compile),
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
    debugIncludesTests       := true,
    debugTestsGenerator     <<= (debugIncludesTests,projectLayout) map {
      (tests,layout) =>
      if (tests)
        (layout.testScalaSource ** "*.scala" get) ++
          (layout.testJavaSource ** "*.java" get)
      else Seq.empty
    },
    setDebug                 := { apkbuildDebug.value(true) },
    setRelease               := { apkbuildDebug.value(false) },
    // I hope packageXXX dependsOn(setXXX) sets createDebug before package
    packageDebug            <<= packageT,
    packageDebug            <<= packageDebug dependsOn setDebug,
    packageRelease          <<= packageT,
    packageRelease          <<= packageRelease dependsOn setRelease,
    sdkPath                 <<= properties { props =>
      (Option(System getenv "ANDROID_HOME") orElse
        Option(props getProperty "sdk.dir")) flatMap { p =>
            val f = file(p + File.separator)
            if (f.exists && f.isDirectory)
              Some(p + File.separator)
            else
              None
          } getOrElse fail(
            "set the env variable ANDROID_HOME pointing to your Android SDK")
    },
    ndkPath                 <<= (thisProject,properties) { (p,props) =>
      (Option(System getenv "ANDROID_NDK_HOME") orElse
        Option(props get "ndk.dir")) flatMap { p =>
        val f = file(p + File.separator)
        if (f.exists && f.isDirectory)
          Some(p + File.separator)
        else
          None
      }
    },
    zipalignPath            <<= ( sdkPath
                                , sdkManager
                                , buildTools
                                , streams) map { (p, m, bt, s) =>
      import SdkConstants._
      val pathInBt = bt.getLocation / FN_ZIPALIGN

      s.log.debug("checking zipalign at: " + pathInBt)

      if (pathInBt.exists)
        pathInBt.getAbsolutePath
      else {
        val zipalign = file(p + OS_SDK_TOOLS_FOLDER + FN_ZIPALIGN)
        if (!zipalign.exists)
          fail("zipalign not found at either %s or %s" format (
            pathInBt, zipalign))
        zipalign.getAbsolutePath
      }
    },
    ilogger                  := { l: Logger => SbtLogger(l) },
    buildToolsVersion        := None,
    sdkLoader               <<= sdkManager map { m =>
      DefaultSdkLoader.getLoader(file(m.getLocation))
    },
    libraryRequests          := Nil,
    builder                 <<= ( sdkLoader
                                , sdkManager
                                , name
                                , ilogger
                                , buildTools
                                , platformTarget
                                , libraryRequests
                                , state) map {
      (ldr, m, n, l, b, t, reqs, s) =>

      val bldr = new AndroidBuilder(n, "android-sdk-plugin",
        new DefaultProcessExecutor(l(s.log)),
        SbtJavaProcessExecutor, SbtProcessOutputHandler(s.log),
        new EvaluationErrorReporter(EvaluationErrorReporter.EvaluationMode.STANDARD) {
          override def handleSyncError(data: String, `type`: Int, msg: String) = {
            s.log.error(s"android sync error: data=$data, type=${`type`}, msg=$msg")
            new SyncIssue {
              override def getType = `type`
              override def getData = data
              override def getMessage = msg
              override def getSeverity = SyncIssue.SEVERITY_ERROR
            }
          }
        },
        l(s.log), false)
      val sdkInfo = ldr.getSdkInfo(l(s.log))
      val targetInfo = ldr.getTargetInfo(t, b.getRevision, l(s.log))
      bldr.setTargetInfo(sdkInfo, targetInfo, reqs map { case ((nm, required)) => new LibraryRequest(nm, required) } asJava)
      bldr
    },
    bootClasspath            := builder.value.getBootClasspath.asScala map Attributed.blank,
    sdkManager              <<= (sdkPath,ilogger, streams) map { (p, l, s) =>
      SdkManager.createManager(p, l(s.log))
    },
    buildTools              := {
      buildToolsVersion.value flatMap { version =>
        Option(sdkManager.value.getBuildTool(FullRevision.parseRevision(version)))
      } getOrElse {
        val tools = sdkManager.value.getLatestBuildTool
        if (tools == null) fail("Android SDK build-tools not found")
        else streams.value.log.debug("Using Android build-tools: " + tools)
        tools
      }
    },
    platformTarget          <<= properties { p =>
      Option(p.getProperty("target")) getOrElse fail(
        "configure project.properties or set 'platformTarget in Android'")
    },
    platform                <<= (sdkManager, platformTarget, thisProject) map {
      (m, p, prj) =>
      val plat = Option(m.getTargetFromHashString(p))
      plat getOrElse fail("Platform %s unknown in %s" format (p, prj.base))
    }
  )) ++ Seq(
    autoScalaLibrary   := {
      ((scalaSource in Compile).value ** "*.scala").get.nonEmpty ||
        (managedSourceDirectories in Compile).value.exists(d =>
          (d ** "*.scala").get.nonEmpty)
    },
    crossPaths        <<= autoScalaLibrary,
    resolvers        <++= (sdkPath in Android) { p =>
      Seq(SdkLayout.googleRepository(p), SdkLayout.androidRepository(p))
    },
    cleanFiles         += (projectLayout in Android).value.bin,
    exportJars         := true,
    unmanagedBase     <<= (projectLayout in Android) (_.libs),
    watchSources     <++= Def.task {
      val filter = new SimpleFileFilter({ f =>
        f.isFile && Character.isJavaIdentifierStart(f.getName.charAt(0))
      })
      val layout = (projectLayout in Android).value
      val extras = (extraResDirectories in Android).value
      (layout.testSources +: layout.jni +: layout.res +: extras) flatMap { path =>
        (path ** filter) get }
    }
  )

  lazy val androidCommands: Seq[Setting[_]] = Seq(
    commands ++= Seq(genAndroid, genAndroidSbt, pidcat, logcat, adbLs, adbShell,
      devices, device, reboot, adbScreenOn, adbRunas, adbKill,
      adbWifi, adbPush, adbPull, adbCat, adbRm, variant, variantClear)
  )

  private def adbCat = Command(
    "adb-cat", ("adb-cat", "Cat a file from device"),
    "Cat a file from device to stdout"
  )(androidFileParser)(adbCatAction)

  private def adbRm = Command(
    "adb-rm", ("adb-rm", "Remove a file from device"),
    "Remove a file from device"
  )(androidFileParser)(adbRmAction)

  private def adbPull = Command(
    "adb-pull", ("adb-pull", "pull a file from device"),
    "Pull a file from device to the local system"
  )(adbPullParser)(adbPullAction)

  private def adbPush = Command(
    "adb-push", ("adb-push", "push a file to device"),
    "Push a file to device from the local system"
  )(adbPushParser)(adbPushAction)

  private def adbShell = Command(
    "adb-shell", ("adb-shell", "execute shell commands on device"),
    "Run a command on a selected android device using adb"
  )(stringParser)(shellAction)

  private def adbRunas = Command(
    "adb-runas", ("adb-runas", "execute shell commands on device as a debuggable package user"),
    "Run a command on a selected android device using adb with the permissions of the current package"
  )(projectAndStringParser)(runasAction)

  private def adbKill = Command(
    "adb-kill", ("adb-kill", "kill the current/specified package"),
    "Kills the process if it is not currently in the foreground"
  )(projectAndStringParser)(killAction)

  private def adbLs = Command(
    "adb-ls", ("adb-ls", "list device files"),
    "List files located on the selected android device"
  )(androidFileParser)(adbLsAction)

  private def logcat = Command(
    "logcat", ("logcat", "grab device logcat"),
    "Read logcat from device without blocking"
  )(stringParser)(logcatAction)

  private def pidcat = Command(
    "pidcat", ("pidcat", "grab device logcat for a package"),
    "Read logcat for a given package, defaults to project package if no arg"
  )(projectAndStringParser)(pidcatAction)

  private def genAndroid = Command(
    "gen-android", ("gen-android", "Create an android project"),
    "Create a new android project built using SBT"
  )(createProjectParser)(createProjectAction)

  private def genAndroidSbt = Command.command(
    "gen-android-sbt", "Create SBT files for existing android project",
    "Creates build.properties, build.scala, etc for an existing android project"
  )(createProjectSbtAction)

  private def device = Command(
    "device", ("device", "Select a connected android device"),
    "Select a device (when there are multiple) to apply actions to"
  )(deviceParser)(deviceAction)

  private def adbScreenOn = Command.command(
    "adb-screenon", "Turn on screen and unlock (if not protected)",
    "Turn the screen on and unlock the keyguard if it is not pin-protected"
  )(adbPowerAction)

  private def adbWifi = Command.command(
    "adb-wifi", "Enable/disable ADB-over-wifi for selected device",
    "Toggle ADB-over-wifi for the selected device"
  )(adbWifiAction)

  private def reboot = Command(
    "adb-reboot", ("adb-reboot", "Reboot selected device"),
    "Reboot the selected device into the specified mode"
  )(rebootParser)(rebootAction)

  private def devices = Command.command(
    "devices", "List connected and online android devices",
    "List all connected and online android devices")(devicesAction)

  private def variant = Command("variant",
    ("variant[/project] <buildType> <flavor>",
      "Load an Android build variant configuration (buildType + flavor)"),
    "Usage: variant[/project] <buildType> <flavor>")(variantParser)(variantAction)

  private def variantClear = Command("variant-reset",
    ("variant-reset", "Clear loaded variant configuration from the project"),
    "Usage: variant-reset[/project]")(projectParser)(variantClearAction)

  def fail[A](msg: => String): A = {
    throw new MessageOnlyException(msg)
  }
}

object SbtJavaProcessExecutor extends JavaProcessExecutor {
  override def execute(javaProcessInfo: JavaProcessInfo, processOutputHandler: ProcessOutputHandler) = {
    val options = ForkOptions(
      envVars = javaProcessInfo.getEnvironment.asScala map { case ((x, y)) => x -> y.toString } toMap,
      runJVMOptions = javaProcessInfo.getJvmArgs.asScala ++
        ("-cp" :: javaProcessInfo.getClasspath :: Nil))
    val r = Fork.java(options, (javaProcessInfo.getMainClass :: Nil) ++ javaProcessInfo.getArgs.asScala)

    new ProcessResult {
      override def assertNormalExitValue() = {
        if (r != 0) throw new ProcessException("error code: " + r)
        this
      }

      override def rethrowFailure() = this

      override def getExitValue = r
    }
  }
}

case class SbtProcessOutputHandler(lg: Logger) extends BaseProcessOutputHandler {
  override def handleOutput(processOutput: ProcessOutput) = {
    processOutput match {
      case p: BaseProcessOutput =>
        val stdout = p.getStandardOutputAsString
        if (!stdout.isEmpty)
          lg.debug(stdout)
        val stderr = p.getErrorOutputAsString
        if (!stderr.isEmpty)
          lg.warn(stderr)
    }
  }
}
case class SbtLogger(lg: Logger) extends ILogger {
  override def verbose(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def info(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def warning(fmt: java.lang.String, args: Object*) {
    lg.warn(String.format(fmt, args:_*))
  }
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) {
    // they don't end the build, so log as a warning
    lg.warn(String.format(fmt, args:_*))
    if (t != null)
      t.printStackTrace()
  }
}
object NullLogger extends ILogger {
  override def verbose(fmt: java.lang.String, args: Object*) = ()
  override def info(fmt: java.lang.String, args: Object*) = ()
  override def warning(fmt: java.lang.String, args: Object*) = ()
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) = ()
}

trait AutoBuild extends Build {
  private def loadLibraryProjects(b: File, props: Properties): Seq[Project] = {
    val p = props.asScala
    (p.keys.collect {
      case k if k.startsWith("android.library.reference") => k
    }.toList.sortWith { (a,b) => a < b } flatMap { k =>
      val layout = ProjectLayout(b/p(k))
      val pkg = pkgFor(layout.manifest)
      (Project(id=pkg, base=b/p(k)) settings(Plugin.androidBuild ++
        Seq(platformTarget in Android := target(b/p(k)),
          libraryProject in Android := true): _*) enablePlugins
            AndroidPlugin) +:
        loadLibraryProjects(b/p(k), loadProperties(b/p(k)))
    }) distinct
  }
  private def target(basedir: File): String = {
    val props = loadProperties(basedir)
    val path = (Option(System getenv "ANDROID_HOME") orElse
      Option(props get "sdk.dir")) flatMap { p =>
      val f = file(p + File.separator)
      if (f.exists && f.isDirectory)
        Some(p + File.separator)
      else
        None
    } getOrElse {
      fail("set ANDROID_HOME or run 'android update project -p %s'"
        format basedir.getCanonicalPath): String
    }
    Option(props getProperty "target") getOrElse {
      val manager = SdkManager.createManager(path, NullLogger)
      val versions = (manager.getTargets map {
        _.getVersion
      } sorted) reverse

      AndroidTargetHash.getPlatformHashString(versions(0))
    }
  }
  private def pkgFor(manifest: File) =
    (XML.loadFile(manifest).attribute("package") get 0 text).replaceAll(
      "\\.", "-")

  override def projects = {

    val projects = super.projects
    if (projects.isEmpty) {
      // TODO search subdirectories to find more complex project structures
      // e.g. root(empty) -> { main-android, library-android }
      val basedir = file(".")
      val layout = ProjectLayout(basedir)
      if (layout.manifest.exists) {

        val props = loadProperties(basedir)
        val libProjects = loadLibraryProjects(basedir, props)

        val project = Project(id=pkgFor(layout.manifest),
          base=basedir).androidBuildWith(libProjects map(a ⇒ a: ProjectReference): _*).settings(
              platformTarget in Android := target(basedir)) enablePlugins
                AndroidPlugin
        project +: libProjects
      } else Nil
    } else {
      // TODO automatically apply androidBuild with all library/sub projects
      // for now, all main projects have to specify androidBuild(deps) manually
      projects map { p =>
        val layout = ProjectLayout(p.base)
        if (layout.manifest.exists) {
          val settings: Seq[Def.Setting[_]] = p.settings
          val prefix = settings.takeWhile(
            _.key.scope.config.toOption exists (_.name != Android.name))
          val tail = settings.dropWhile(
            _.key.scope.config.toOption exists (_.name != Android.name))
          val platform = platformTarget in Android := target(p.base)
          p.settings(prefix ++ Plugin.androidBuild ++ (platform +: tail): _*)
            .enablePlugins(AndroidPlugin)
        } else p
      }
    }
  }
}
