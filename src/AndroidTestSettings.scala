package android

import Keys._
import Keys.Internal._
import Tasks._
import sbt._
import sbt.Keys._
/**
  * @author pfnguyen
  */
trait AndroidTestSettings extends AutoPlugin {
  override def projectSettings = super.projectSettings ++ inConfig(Compile)(List(
    sourceGenerators += debugTestsGenerator.taskValue
  )) ++ inConfig(Android)(List(
    test                    <<= testTaskDef,
    test                    <<= test dependsOn (compile in Android, install),
    testOnly                <<= testOnlyTaskDef,
    testAggregate           <<= testAggregateTaskDef,
    instrumentTestTimeout    := 180000,
    instrumentTestRunner     := "android.test.InstrumentationTestRunner",
    debugTestsGenerator     <<= (debugIncludesTests,projectLayout) map {
      (tests,layout) =>
        if (tests)
          (layout.testScalaSource ** "*.scala").get ++
            (layout.testJavaSource ** "*.java").get
        else Nil
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
   dependencyClasspath in AndroidTestInternal := (dependencyClasspath in AndroidTest).value ++ (dependencyClasspath in Runtime).value
  )
}
