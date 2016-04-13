package android

import java.util.Properties

import android.Dependencies.LibraryProject
import com.android.ide.common.process._
import com.android.tools.lint.LintCliFlags
import com.hanhuy.sbt.bintray.UpdateChecker
import sbt._
import sbt.Cache.StringFormat
import sbt.Keys._
import com.android.builder.core.{AndroidBuilder, LibraryRequest}
import com.android.builder.sdk.DefaultSdkLoader
import com.android.sdklib.{AndroidTargetHash, IAndroidTarget, SdkVersionInfo}
import com.android.SdkConstants
import java.io.{File, PrintWriter}

import com.android.repository.Revision
import com.android.sdklib.repositoryv2.AndroidSdkHandler

import scala.collection.JavaConverters._
import scala.util.Try
import scala.xml.XML
import Keys._
import Keys.Internal._
import Tasks._
import Resources.ANDROID_NS
import Commands._
import Dependencies.LibrarySeqOps
import parsers.sbinaryFileFormat

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
      projectLayout := ProjectLayout(p.base.getCanonicalFile, Some(base.getCanonicalFile)),
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
        transitiveAars <++= aars in p,
        collectResources <<=
          collectResources dependsOn (compile in Compile in p),
        compile in Compile <<= compile in Compile dependsOn(
          packageT in Compile in p),
        localProjects +=
          LibraryProject((projectLayout in p).value)((outputLayout in p).value),
        localProjects := {
          (localProjects.value ++
            (localProjects in p).value).distinctLibs
        }
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

  def useSupportVectors = Seq(
    renderVectorDrawables := false,
    aaptAdditionalParams += "--no-version-vectors"
  )

  def buildJar = Seq(
    manifest := <manifest package="com.hanhuy.sbt.placeholder">
      <application/>
    </manifest>,
    processManifest := {
      implicit val out = outputLayout.value
      val layout = projectLayout.value
      val manifestTarget = layout.processedManifest
      manifestTarget.getParentFile.mkdirs()
      XML.save(manifestTarget.getAbsolutePath, manifest.value, "utf-8")
      manifestTarget
    },
    buildConfigGenerator := Nil,
    rGenerator := Nil,
    debugIncludesTests := false,
    libraryProject := true,
    publishArtifact in (Compile,packageBin) := true,
    publishArtifact in (Compile,packageSrc) := true,
    mappings in (Compile,packageSrc) ++= (managedSources in Compile).value map (s => (s,s.getName)),
    lintFlags := {
      val flags = lintFlags.value
      implicit val output = outputLayout.value
      val layout = projectLayout.value
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
  def buildAar = Seq(libraryProject := true) ++
      addArtifact(aarArtifact , packageAar)

  def buildApklib = Seq(libraryProject := true) ++
    addArtifact(apklibArtifact, packageApklib)

  private lazy val allPluginSettings: Seq[Setting[_]] = inConfig(Compile) (Seq(
    compile <<= ( compile
                , lintDetectors
                , lintFlags
                , lintEnabled
                , lintStrict
                , projectLayout
                , outputLayout
                , minSdkVersion
                , targetSdkVersion
                , streams) map { (c, ld, f, en, strict, layout, o, minSdk, tgtSdk, s) =>
      dsl.checkVersion("minSdkVersion", minSdk)
      dsl.checkVersion("targetSdkVersion", tgtSdk)
      implicit val output = o
      if (en)
        AndroidLint(layout, f, ld, strict, minSdk, tgtSdk, s)
      c
    },
    sourceManaged               := projectLayout.value.gen,
    unmanagedSourceDirectories <<= projectLayout (l =>
      Set(l.sources, l.javaSource, l.scalaSource).toSeq),
    // was necessary prior to 0.13.8 to squelch "No main class detected" warning
    //packageOptions in packageBin := Package.JarManifest(new java.util.jar.Manifest) :: Nil,
    packageConfiguration in packageBin <<= ( packageConfiguration in packageBin
                                           , baseDirectory
                                           , libraryProject
                                           , projectLayout
                                           , outputLayout
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
    resourceDirectory  := projectLayout.value.resources,
    scalaSource        := projectLayout.value.scalaSource,
    javaSource         := projectLayout.value.javaSource,
    unmanagedJars     <<= unmanagedJarsTaskDef,
    // doesn't work properly yet, not for intellij integration
    //managedClasspath  <<= managedClasspathTaskDef,
    classDirectory     := {
      implicit val output = outputLayout.value
      projectLayout.value.classes
    },
    sourceGenerators   := sourceGenerators.value ++ List(
      rGenerator.taskValue,
      typedResourcesGenerator.taskValue,
      aidl.taskValue,
      buildConfigGenerator.taskValue,
      renderscript.taskValue,
      debugTestsGenerator.taskValue,
      cleanForR.taskValue,
      Def.task {
        (apklibs.value ++ autolibs.value flatMap { l =>
          (l.layout.javaSource ** "*.java").get ++
            (l.layout.scalaSource ** "*.scala").get
        }) map (_.getAbsoluteFile)
      }.taskValue
    ),
    packageT          <<= packageT dependsOn compile,
    javacOptions      <<= ( javacOptions
                          , bootClasspath
                          , apkbuildDebug
                          , retrolambdaEnabled) map {
      (o,boot, debug, re) =>
      // users will want to call clean before compiling if changing debug
      val debugOptions = if (debug()) Seq("-g") else Seq.empty
      val bcp = boot.map(_.data) mkString File.pathSeparator
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
    scalacOptions     <<= (scalacOptions, bootClasspath) map { (o,boot) =>
      // scalac has -g:vars by default
      val bcp = boot.map(_.data) mkString File.pathSeparator
      o ++ Seq("-bootclasspath", bcp, "-javabootclasspath", bcp)
    }
  )) ++ inConfig(Test) (Seq(
    exportJars         := false,
    managedClasspath <++= platform map { t =>
      t.getOptionalLibraries.asScala map { l =>
        Attributed.blank(l.getJar)
      }
    },
    scalacOptions in console    := Seq.empty
  )) ++ inConfig(Android) (Classpaths.configSettings ++ Seq(
    // fix for sbt 0.13.11
    artifactPath in packageBin  := (artifactPath in (Compile,packageBin)).value,
    flavors                     := Map.empty,
    buildTypes                  := Map.empty,
    pluginSettingsLoaded        := {
      if (pluginSettingsLoaded.?.value.isDefined)
        fail(s"androidBuild has been applied to project ${thisProject.value.id} more than once")
      true
    },
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
    sourceDirectory             := projectLayout.value.testSources,
    managedSources              := Nil,
    unmanagedSourceDirectories <<= projectLayout (l =>
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
    // end for Classpaths.configSettings
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
      // try to filter out duplicate aar libraries as well
      // it seems internal-dependency-classpath already filters out "provided"
      // from other projects, now, just filter out our own "provided" lib deps
      // do not filter out provided libs for scala, we do that later
      val (withMID,withoutMID) = cp collect {
        case x if x.get(moduleID.key).isDefined =>
          (x,(x.get(moduleID.key),x.data.getName))
        case x => (x,(None, x.data.getName))
      } partition (_._2._1.isDefined)
      (withMID.groupBy(_._2).values.map(_.head._1) ++  withoutMID.map(_._1)).filterNot { _.get(moduleID.key) exists { m =>
          m.organization != "org.scala-lang" &&
            (pvd exists (p => m.organization == p.organization &&
              m.name == p.name))
        }
      }.groupBy(_.data).map { case (k,v) => v.head }.toList
    },
    updateCheck              := {
      val log = streams.value.log
      if (BuildInfo.name == "android-sdk-plugin") {
        log.warn("NOTICE: final version published at `com.hanhuy.sbt % android-sdk-plugin`")
        log.warn("""MIGRATION: `addSbtPlugin("org.scala-android" % "sbt-android" % "1.6.0")`""")
      }
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
    aaptAggregate           <<= aaptAggregateTaskDef,
    aaptAdditionalParams     := Nil,
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
      }(Set((l.generatedSrc ** "R.java").get: _*))
      Seq.empty[File]
    },
    buildConfigGenerator    <<= buildConfigGeneratorTaskDef,
    buildConfigOptions       := {
      val s = state.value
      val prj = thisProjectRef.value
      val pkg = applicationId.value
      val (buildType,flavor) = VariantSettings.variant(s).status.getOrElse(
        prj, (None,None))
      List(
        ("String", "BUILD_TYPE", s""""${buildType getOrElse ""}""""),
        ("String", "FLAVOR", s""""${flavor getOrElse ""}""""),
        ("String", "APPLICATION_ID", s""""$pkg"""")
      ) ++
        versionName.value.toList.map(n => ("String", "VERSION_NAME", s""""$n"""")) ++
        versionCode.value.toList.map (c => ("int", "VERSION_CODE", c.toString)
      )
    },
    resValues                := Nil,
    resValuesGenerator      <<= resValuesGeneratorTaskDef,
    rGenerator              <<= rGeneratorTaskDef,
    rGenerator              <<= rGenerator dependsOn renderscript,
    ndkJavah                <<= ndkJavahTaskDef,
    ndkAbiFilter             := Nil,
    ndkEnv                   := Nil,
    ndkArgs                  := Nil,
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
    localProjects           <<= (baseDirectory, properties, outputLayout) { (b,p,o) =>
      loadLibraryReferences(b, p)(o)
    },
    libraryProjects          := localProjects.value ++ apklibs.value ++ aars.value,
    libraryProject          <<= properties { p =>
      Option(p.getProperty("android.library")) exists { _.equals("true") } },
    checkAars               <<= checkAarsTaskDef,
    dexInputs               <<= dexInputsTaskDef,
    dexAggregate            <<= dexAggregateTaskDef,
    collectResourcesAggregate <<= collectResourcesAggregateTaskDef,
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
    dexMaxProcessCount       := java.lang.Runtime.getRuntime.availableProcessors,
    dexMulti                 := false,
    dexMainClasses           := Seq.empty,
    dexMinimizeMain          := false,
    dexAdditionalParams      := Seq.empty,
    dexMainClassesConfig    <<= dexMainClassesConfigTaskDef dependsOn (packageT in Compile),
    platformJars            <<= platform { p =>
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
    manifestOverlays         := Seq.empty,
    processManifest         <<= processManifestTaskDef storeAs processManifest,
    manifest                <<= manifestPath map { m =>
      if (!m.exists)
        fail("cannot find AndroidManifest.xml: " + m)
      XML.loadFile(m)
    },
    versionCode              := {
      manifest.value.attribute(ANDROID_NS, "versionCode").map(_.head.text.toInt)
    },
    versionName              := {
      manifest.value.attribute(
        ANDROID_NS, "versionName").map(_.head.text) orElse Some(version.value)
    },
    packageForR             <<= manifest map { m =>
      m.attribute("package").get.head.text
    },
    applicationId           <<= Def.task {
      packageName.?.value.fold(manifest.value.attribute("package").head.text) { p =>
        streams.value.log.warn(
          "'packageName in Android' is deprecated, use 'applicationId'")
        p
      }
    } storeAs applicationId,
    targetSdkVersion         := {
      val m = manifest.value
      val usesSdk = m \ "uses-sdk"
      val v = String.valueOf(platformApi.value)
      if (usesSdk.isEmpty) v else
        usesSdk(0).attribute(ANDROID_NS, "targetSdkVersion").fold(v) { _.head.text }
    },
    minSdkVersion            := {
      val m = manifest.value
      val usesSdk = m \ "uses-sdk"
      if (usesSdk.isEmpty) "7" else
        usesSdk(0).attribute(ANDROID_NS, "minSdkVersion").fold("7") { _.head.text }
    },
    proguardVersion          := "5.0",
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
    useProguard             <<= proguardScala,
    useSdkProguard          <<= proguardScala (!_),
    useProguardInDebug      <<= proguardScala,
    extraResDirectories         := Nil,
    extraAssetDirectories       := Nil,
    renderVectorDrawables    := true,
    collectResources        <<= collectResourcesTaskDef,
    collectResources        <<= collectResources dependsOn renderscript,
    collectResources        <<= collectResources dependsOn resValuesGenerator,
    collectResources        <<= collectResources dependsOn checkAars,
    shrinkResources          := false,
    resourceShrinker        <<= resourceShrinkerTaskDef,
    packageResources        <<= packageResourcesTaskDef dependsOn rGenerator,
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
    debugIncludesTests       := true,
    debugTestsGenerator     <<= (debugIncludesTests,projectLayout) map {
      (tests,layout) =>
      if (tests)
        (layout.testScalaSource ** "*.scala").get ++
          (layout.testJavaSource ** "*.java").get
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
      val cached = SdkLayout.androidHomeCache
      (Option(System getenv "ANDROID_HOME") orElse
        Option(props getProperty "sdk.dir")) flatMap { p =>
        val f = file(p + File.separator)
        if (f.exists && f.isDirectory) {
          cached.getParentFile.mkdirs()
          IO.writeLines(cached, p :: Nil)
          Some(p + File.separator)
        } else None
      } orElse SdkLayout.sdkFallback(cached) getOrElse fail(
        "set the env variable ANDROID_HOME pointing to your Android SDK")
    },
    ndkPath                 <<= (thisProject,properties) { (p,props) =>
      val cached = SdkLayout.androidNdkHomeCache
      (Option(System getenv "ANDROID_NDK_HOME") orElse
        Option(props getProperty "ndk.dir")) flatMap { p =>
        val f = file(p + File.separator)
        if (f.exists && f.isDirectory) {
          cached.getParentFile.mkdirs()
          IO.writeLines(cached, p :: Nil)
          Some(p + File.separator)
        } else None
      } orElse SdkLayout.sdkFallback(cached)
    },
    zipalignPath            <<= ( sdkPath
                                , sdkManager
                                , buildTools
                                , sLog) { (p, m, bt, s) =>
      import SdkConstants._
      val pathInBt = bt.getLocation / FN_ZIPALIGN

      s.debug("checking zipalign at: " + pathInBt)

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
    ilogger                  := {
      val logger = SbtILogger()

      {
        l =>
          logger(l)
          logger
      }
    },
    buildToolsVersion        := None,
    sdkLoader               <<= sdkManager { m =>
      DefaultSdkLoader.getLoader(m.getLocation)
    },
    libraryRequests          := Nil,
    builder                 <<= ( sdkLoader
                                , sdkManager
                                , name
                                , ilogger
                                , buildTools
                                , platformTarget
                                , libraryRequests
                                , sLog) {
      (ldr, m, n, l_, b, t, reqs, log) =>
      val l = l_(log)
      val l2 = SbtAndroidErrorReporter()
      val bldr = new AndroidBuilder(n, "android-sdk-plugin",
        new DefaultProcessExecutor(l),
        SbtJavaProcessExecutor,
        l2, l, false)
      val sdkInfo = ldr.getSdkInfo(l)
      val targetInfo = ldr.getTargetInfo(t, b.getRevision, l)
      bldr.setTargetInfo(sdkInfo, targetInfo,
        reqs.map { case ((nm, required)) =>
          new LibraryRequest(nm, required) }.asJava)

      { logger =>
        l_(logger)
        l2(logger)
        bldr
      }
    },
    bootClasspath            := builder.value(sLog.value).getBootClasspath(false).asScala map Attributed.blank,
    sdkManager              <<= sdkPath { p =>
      AndroidSdkHandler.getInstance(file(p))
    },
    buildTools              := {
      buildToolsVersion.value flatMap { version =>
        Option(sdkManager.value.getBuildToolInfo(Revision.parseRevision(version),
          SbtAndroidProgressIndicator(sLog.value)))
      } getOrElse {
        val tools = sdkManager.value.getLatestBuildTool(SbtAndroidProgressIndicator(sLog.value))
        if (tools == null) fail("Android SDK build-tools not found")
        else sLog.value.debug("Using Android build-tools: " + tools)
        tools
      }
    },
    platformTarget          <<= (properties,thisProject) { (p,prj) =>
      Option(p.getProperty("target")) getOrElse fail(
        prj.id + ": configure project.properties or set 'platformTarget'")
    },
    platformApi             := {
      // SDK makes some spurious warnings here, ignore them with NullLogger
      val tgt = sdkLoader.value.getTargetInfo(platformTarget.value,
        buildTools.value.getRevision, SbtDebugLogger(sLog.value))
      tgt.getTarget.getVersion.getApiLevel
    },
    platform                <<= (sdkManager, platformTarget, thisProject, sLog) {
      (m, p, prj, s) =>
        val logger = SbtAndroidProgressIndicator(s)
        val plat = Option(m.getAndroidTargetManager(logger).getTargetFromHashString(p, logger))
        plat getOrElse fail("Platform %s unknown in %s" format (p, prj.base))
    }
  )) ++ Seq(
    autoScalaLibrary   := {
      ((scalaSource in Compile).value ** "*.scala").get.nonEmpty ||
        (managedSourceDirectories in Compile).value.exists(d =>
          (d ** "*.scala").get.nonEmpty)
    },
    crossPaths        <<= autoScalaLibrary,
    resolvers        <++= sdkPath { p =>
      Seq(SdkLayout.googleRepository(p), SdkLayout.androidRepository(p))
    },
    cleanFiles         += projectLayout.value.bin,
    exportJars         := true,
    unmanagedBase      := projectLayout.value.libs,
    watchSources     <++= Def.task {
      val filter = new SimpleFileFilter({ f =>
        f.isFile && Character.isJavaIdentifierStart(f.getName.charAt(0))
      })
      val layout = projectLayout.value
      val extras = extraResDirectories.value.map(_.getCanonicalFile).distinct
      (layout.testSources +: layout.jni +: layout.res +: extras) flatMap { path =>
        (path ** filter).get }
    },
    libraryDependencies <+= Def.setting("net.sf.proguard" % "proguard-base" % proguardVersion.value % AndroidInternal.name),
    managedClasspath in AndroidInternal := Classpaths.managedJars(AndroidInternal, classpathTypes.value, update.value)
  )

  lazy val androidCommands: Seq[Setting[_]] = Seq(
    commands ++= Seq(genAndroid, genAndroidSbt,
      pidcat, pidcatGrep, logcat, logcatGrep, adbLs, adbShell,
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

  private def logcatGrep = Command(
    "logcat-grep", ("logcat-grep", "grep through device logcat"),
    "Read logcat from device without blocking, filtered by regex"
  )(stringParser)(logcatGrepAction)

  private def pidcat = Command(
    "pidcat", ("pidcat", "grab device logcat for a package"),
    "Read logcat for a given package, defaults to project package if no arg"
  )(projectAndStringParser)(pidcatAction)

  private def pidcatGrep = Command(
    "pidcat-grep", ("pidcat-grep", "grep through device logcat for a package"),
    "Read logcat for a given package, defaults to project package if no arg, filtered by regex"
  )(projectAndStringParser)(pidcatGrepAction)

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

@deprecated("Build.scala files are going away in sbt 1.0", "1.6.0")
trait AutoBuild extends Build {
  private def loadLibraryProjects(b: File, props: Properties): Seq[Project] = {
    val p = props.asScala
    (p.keys.collect {
      case k if k.startsWith("android.library.reference") => k
    }.toList.sortWith { (a,b) => a < b } flatMap { k =>
      val layout = ProjectLayout(b/p(k))
      val pkg = pkgFor(layout.manifest)
      (Project(id=pkg, base=b/p(k)) settings(Plugin.androidBuild ++
        Seq(platformTarget := target(b/p(k)),
          libraryProject := true): _*) enablePlugins
            AndroidPlugin) +:
        loadLibraryProjects(b/p(k), loadProperties(b/p(k)))
    }).distinct
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
      val handler = AndroidSdkHandler.getInstance(file(path))
      val manager = handler.getAndroidTargetManager(NullProgressIndicator)
      val versions = manager.getTargets(NullProgressIndicator).asScala.toList.map {
        _.getVersion
      }.sorted.reverse

      AndroidTargetHash.getPlatformHashString(versions.head)
    }
  }
  private def pkgFor(manifest: File) =
    XML.loadFile(manifest).attribute("package").get.head.text.replace('.', '-')

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
              platformTarget := target(basedir)) enablePlugins
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
          val platform = platformTarget := target(p.base)
          p.settings(prefix ++ Plugin.androidBuild ++ (platform +: tail): _*)
            .enablePlugins(AndroidPlugin)
        } else p
      }
    }
  }
}
