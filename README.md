# Android SDK Plugin for SBT #

Current version is 1.2.9

## Description ##

This is an easy-to-use plugin for existing and newly created android
projects.  It is tested and developed against 0.12.x and 0.13.x.

The plugin supports normal android projects and projects that reference
library projects. 3rd party libraries can be included by placing them in
`libs` as in regular projects, or they can be added by using sbt's
`libraryDependencies` feature.

Features not support from the regular android build yet are compiling `NDK`
code. Although, `NDK` libraries will be picked up from `libs` as in typical
ant builds (or `src/main/jni` if you're using the new Gradle layout).

## New features in 1.3.x (work in progress) ##

* Updated to com.android.tools.build 0.8.x
  * Now requires android build-tools 19.0.0 or newer
* `android:dex` task now returns a folder for the output dex not a `classes.dex`
  file.

## New features in 1.2.x (last version: 1.2.9 or 1.2.10) ##

* Add setting `android:debug-includes-tests` (default = true) to automatically
  include instrumented test cases in the debug APK instead of using a separate
  test APK. This feature improves IntelliJ testing integration.
  * As a result of this new feature, if there are any `libraryDependencies` in
    `test` that must be honored, the setting must be disabled, and a separate
    test APK must be created. An alternative is to include the test dependencies
    in the normal compile. Proguard will automatically strip these out in
    release builds if they are unused.
  * This setting may be ignored, or set to `false` if one does not have tests
    or does not want to include the test cases in the debug package.
  * If the setting is disabled, test cases will be generated into a test APK
    when running `android:test`
  * When generating release builds, it is important to `clean`, otherwise
    test artifacts may be left over and present in the released apk.
  * When using included tests, it is necessary to add the following proguard
    options, or else proguard will mistakenly remove test cases from the
    output:

    ```
    proguardOptions in Android ++= Seq(
      "-keep public class * extends junit.framework.TestCase",
      "-keepclassmembers class * extends junit.framework.TestCase { *; }"
    )
    ```
* Add ability to disable manifest merging if upstream libraries have bad
  manifest settings, set `mergeManifests in Android := false`, default is
  `true`
  * Disabling manifest merging will remove automatic import of Activities,
    Services, BroadcastReceivers, etc. from the library's manifest into the
    main application manifest
* Increase test timeout to 3 minutes, from 5 seconds, configurable by using the
  `instrumentTestTimeout` setting key, in milliseconds
* Add `apkbuildExcludes` setting to skip/ignore duplicate files, an error like
  this:
  ```
  [info] com.android.builder.packaging.DuplicateFileException: Duplicate files copied in APK META-INF/LICENSE.txt
  [info]  File 1: /path1/some.jar
  [info]  File 2: /path2/some.jar
  ```
  Can be rectified by setting
  `apkbuildExcludes in Android += "META-INF/LICENSE.txt"`

## New features in 1.1.x (last version: 1.1.2) ##

* Automatically load declared library projects from `project.properties`,
  `build.scala` is no longer necessary to configure the library projects,
  unless other advanced features are necessary (this means that any
  android project that only uses library projects does not need to use
  multi-project configurations).
  * For those not using `project.properties` an alternative is to add
    `android.Dependencies.AutoLibraryProject(path)`s to `local-projects`

    ```
    import android.Keys._
    import android.Dependencies.AutoLibraryProject

    localProjects in Android <+= (baseDirectory) {
      b => AutoLibraryProject(b / ".." / "my-library-project")
    }
    ```
* `version-code` and `version-name` are defaulted to no-ops (no overrides)
  * They can be set programmatically using an sbt `Command`
* instrumented tests now go into `src/instrumentTest` in gradle-layout projects
  * a test `AndroidManifest.xml` will be automatically generated if not present

## New features in 1.0.x (last version: 1.0.8) ##

* Customizable proguard caching!
* Proguard cache rules are defined using the `proguardCache in Android`
  setting, the rules are of type `android.Keys.ProguardCache` and can be
  defined like so:
  * The default cache rule is defined as
    `ProguardCache("scala") % "org.scala-lang"`, this caches all scala
    core libraries automatically.
  * `proguardCache in Android += ProguardCache("play") % "play" %% "play-json"`
    will match all packages and classes contained in `play.**` from the
    module defined by the organization name `play` and module name `play-json`.
    `%%` specifies that the module name should be cross-versioned for
    detecting a match. `%` can be used to select the plain module name
    without scala cross-versioning. If a module name is not specified,
    all libraries in the selected organization will be cached with the
    package names passed to `ProguardCache()`
  * `... <+= baseDirectory (b => ProguardCache("android.support.v4") << (b / "libs / "android-support-v4.jar))"`
    will cache `android.support.v4.**` from the local jar
    `libs/android-support-v4.jar`
  * All packages within a jar to be cached _MUST_ be declared in the rule
    or else many NoClassDefFound errors will ensue!
  * Multiple packages may be specified in a cache rule:
    `ProguardCache("package1", "package2", "package3") ...`
  * All ProguardCache rules must be associated with a module-org+name or a
    local jar file.
  * Defining many cache rules will result in a higher cache-miss rate, but
    will dramatically speed up builds on cache-hits; choose libraries and
    caching rules carefully to balance the the cache-hit ratio. Large,
    multi-megabyte libraries should always be cached to avoid hitting the
    dex-file method-limit.
  * Transitive dependencies are not cached automatically, those rules need
    to be defined explicitly.
* Fixes NoSuchMethodError sometimes occuring when re-building after a
  proguard cache-miss (clear dex file on the first cache-hit build after
  proguarding; caused by dex incremental builds)

## New features in 0.9.x (last version: 0.9.3) ##

* If sbt-idea is used, 1.6.0 and newer is required (alternatively, can use my
  fork at
  `addSbtPlugin("com.hanhuy.sbt" % "sbt-idea" % "1.6.0")`)
* Instrumented (on-device) testing
  * Test cases may *only* use a subset of scala used by the main application,
    referencing anything above and beyond that used by the main application
    will cause NoClassDefFoundError because tests are not proguarded. Java
    may be used without restriction. Embedding other languages remains a
    possibility.
* Re-enable incremental resource merging
* Dramatically improve scala-based project build times; proguard-caching is
  performed automatically on debug builds
* Better default proguard configuration for scala projects (no need to
  specify explicit rules unless there's something you absolutely need)
* Add a better method of specifying local-projects besides only in
  project.properties, or overriding library-projects in a convoluted manner.
  use `localProjects in Android += android.Dependencies.LibraryProject(lib_project.base)`
  settings to add library projects without declaring them in
  `project.properties` or otherwise
* Add `local-aars` setting to allow the use of AARs without a repo.
* Add `android.ArbitraryProject` load any project you want from a git repo,
  see [this example](https://gist.github.com/pfn/6238004) for details.

## New features in 0.8.x (last version: 0.8.2) ##

* NOTICE: sbt-0.11 is no longer supported with this release
* Library projects can now declare activities and services and other
  goodies within their own manifests. They will be merged into the main
  project manifest at build time. (AndroidBuilder.processManifest)
* `packageName` can now be overridden to generate an APK with a package name
  very useful for testing release vs. debug builds (change the packageName
  for a debug build and keep both applications installed side-by-side)
* `versionCode`, `versionName`, `targetSdkVersion` and `minSdkVersion` can
  now be munged
  into manifest automatically by configuring the corresponding settings
* Android SDK logs are now visible within sbt's logger,
  `last android:builder` to see log output generated by AndroidBuilder
* Incremental resources merging! Saves time merging when you have
  multiple projects with many resources.
* Many delta build issues fixed.

## New features in 0.7.x (last version: 0.7.8) ##

* Projects can now follow an ant-style or gradle-style layout. The location
  of `AndroidManifest.xml` will auto-select which layout to use, if it is
  at the top-level, ant-style will be selected and under `src/main` will
  choose gradle-style. (more testing needs to be performed against the
  gradle-style layouts)
  * Gradle-style project layouts need to be created by other means, either
    using the gradle plugin, IDEA, maven, sbt-android or by hand.
  * When using Gradle-style project layouts without properties files
    platformTarget in Android should be set manually to the string name
    of the platform as listed in `android list targets`. Any settings
    normally loaded from a `.properties` file should also be configured
    in the build settings as necessary.
* Consuming apklib and aar artifacts from maven or ivy
* Producing and publishing apklib and aar artifacts to maven or ivy
* Switch to using `com.android.build.AndroidBuilder` for many operations
  to maintain parity with Google's own android build process. Like that used
  in the new Gradle build
* All plugin classes have been moved into the `android` package namespace.
* Simplify configuration with the new `androidBuild`, `androidBuildAar`,
  `androidBuildApklib`, `buildApklib`, and `buildAar` shortcuts located in
  `android.Plugin`

## Description ##

This is an easy-to-use plugin for existing and newly created android
projects.  It is tested and developed against 0.12.4; while building
against 0.13 is also in progress.

The plugin supports normal android projects and projects that reference
library projects. 3rd party libraries can be included by placing them in
`libs` as in regular projects, or they can be added by using sbt's
`libraryDependencies` feature.

Features not support from the regular android build yet are compiling `NDK`
code. Although, `NDK` libraries will be picked up from `libs` as in typical
ant builds (or `src/main/jni` if you're using the new Gradle layout).

## Example projects ##

* A variety of my own projects can be found on github that use this plugin
* In addition to this, a growing collection of tests can be found under
  `sbt-test/android-sdk-plugin/`. Over time, this will grow to be a larger
  set of examples of how to use the plugin in various configurations.
* Tests can be run via `sbt scripted` and require `ANDROID_HOME` to be set
  in addition to having platform `android-17` installed.
* All tests have auto-generated `build.properties` and `auto_plugins.sbt`
  files that set the current version of sbt and the android-sdk-plugin to use
  for testing.

## Usage ##

1. Install sbt (from http://www.scala-sbt.org or use your local packaging
   system like macports, brew, etc.) -- make sure the Android SDK is fully
   updated (minimum build-tools 17.0.0 and up)
2. Create a new android project using `android create project` or Eclipse
   * Instead of creating a new project, one can also do
     `android update project` to make sure everything is properly setup
     in an existing project.
   * Alternatively, create a project by hand, or with Android Studio for
     the new project layout style.
   * Instead of keeping local.properties up-to-date, you may set the
     environment variable `ANDROID_HOME` pointing to the path where the
     Android SDK is unpacked. This will bypass the requirement of having
     to run `android update project` on existing projects.
3. Create a directory named `project` within your project and add the file
   `project/plugins.sbt`, in it, add the following line:

    ```
    addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.2.9")
    ```

4. Create `project/build.properties` and add the following line:

   ```
   sbt.version=0.12.4 # newer versions may be used instead
   ```

5. Create a file named `build.sbt` in the root of your project and add the
   following lines with a blank line between each:
   * `android.Plugin.androidBuild`
   * `name := YOUR-PROJECT-NAME` (optional, but you'll get a stupid default
     if you don't set it)
   * If you are not using an ant-based project, you will need to specify
     the android build target, you do this with
     `platformTarget in Android := "android-N"`
   * An example of what build.sbt should look like can be found at
     https://gist.github.com/pfn/5872691

6. Now you will be able to run SBT, some available commands in sbt are:
   * `compile`
     * Compiles all the sources in the project, java and scala
     * Compile output is automatically processed through proguard if there
       are any Scala sources, otherwise; it can be enabled manually.
   * `android:package-release`
      * Builds a release APK and signs it with a release key if configured
   * `android:package-debug`
      * Builds a debug APK and signs it using the debug key
   * `android:package`
     * Builds an APK for the project of the last type selected, by default
       `debug`
   * Any task can be repeated continuously whenever any source code changes
     by prefixing the command with a `~`. `~ android:package-debug`
     will continuously build a debug build any time one of the project's
     source files is modified.
7. If you want android-sdk-plugin to automatically sign release packages
   add the following lines to `local.properties` (or any file.properties of
   your choice that you will not check in to source control):
   * `key.alias: YOUR-KEY-ALIAS`
   * `key.store: /path/to/your/.keystore`
   * `key.store.password: YOUR-KEY-PASSWORD`
   * `key.store.type: pkcs12` (optional, defaults to `jks`)

## Advanced Usage ##

* IDE integration
  * The primary IDE recommendation is IntelliJ, not Android Studio
    nor Eclipse.
  * To generate project files for loading into IntelliJ, use the `sbt-idea`
    plugin by adding
    `addSbtPlugin("com.hanhuy.sbt" % "sbt-idea" % "1.6.0")` to your
    `project/plugins.sbt` and running the command `sbt gen-idea` (*NOTE*:
    temporarily use my sbt-idea fork until my pull request is merged).
  * When loading a project into IntelliJ, it is recommended that the `SBT`
    and `Scala` plugins are installed; the `SBT` plugin allows replacing the
    default `Make` builder with sbt, enabling seamless builds from the IDE.
  * The best practice is to set the IDE's run task to invoke sbt
    `android:package` instead of `Make`; this is found under the Run
    Configurations
  * The `Scala` plugin is still useful for non-Scala projects in order to
    edit sbt build files from inside the IDE.
* Consuming apklib and aar artifacts from other projects
  * `import android.Dependencies.{apklib,aar}` to use apklib() and aar()
  * `libraryDependencies += apklib("groupId" % "artifactId" % "version", "optionalArtifactFilename")`
    * Basically, wrap the typical dependency specification with either
      apklib() or aar() to consume the library
    * For library projects in a multi-project build that transitively include
      either aar or apklibs, you will need to add a dependency statement
      into your main-project's settings:
    * `collectResources in Android <<= collectResources in Android dependsOn (compile in Compile in otherLibraryProject)`
    * Alternatively, the `androidBuild()` overload may be used to specify
      all dependency library-projects which should relieve this problem.
* Using the google gms play-services aar:

    ```
    libraryDependencies +=
      "com.google.android.gms" % "play-services" % "3.1.36"
    ```

* Generating apklib and/or aar artifacts
  * To specify that your project will generate and publish either an `aar`
    or `apklib` artifact simply change the `android.Plugin.androidBuild`
    line to one of the variants that will build the desired output type.
    * For `apklib` use `android.Plugin.androidBuildApklib`
    * For `aar` use `android.Plugin.androidBuildAar`
  * Alternatively, use `android.Plugin.buildAar` and/or
    `android.Plugin.buildApklib` in addition to any of the variants above
    * In build.sbt, add `android.Plugin.buildAar` and/or
      `android.Plugin.buildApklib` on a new line.
    * It could also be specified, for example, like so:
      `android.Plugin.androidBuild ++ android.Plugin.buildAar`
* Multi-project builds
  * Several documented examples can be found at the following links, they
    cover a variety of situations, from multiple java projects, to mixed
    java and android projects and fully scala projects.
    * https://gist.github.com/pfn/5872427
    * https://gist.github.com/pfn/5872679
    * https://gist.github.com/pfn/5872770
  * See [Working with Android library projects](https://github.com/pfn/android-sdk-plugin/wiki/Working-with-Android-library-projects) 
    in the Wiki for detailed instructions on configuring Android library
    projects
  * When using multi-project builds in Scala, where library projects have
    scala code, but the main project(s) do(es) not, you will need to specify
    that proguard must run. To do this, the following must be set for each
    main project: `proguardScala in Android := true`
* Configuring `android-sdk-plugin` by editing build.sbt
  * `import android.Keys._` at the top to make sure you can use the plugin's
    configuration options
  * Add configuration options according to the sbt style:
    * `useProguard in Android := true` to enable proguard. Note: if you
      disable proguard for scala, you *must* specify uses-library on a
      pre-installed scala lib on-device. Pre-dexing the scala libs is not
      supported.
  * Configurable keys can be discovered by typing `android:<tab>` at the
    sbt shell
* Configuring proguard, some options are available
  * `proguardOptions in Android += Seq("-dontobfuscate", "-dontoptimize")` -
    will tell proguard not to obfuscute nor optimize code (any valid proguard
    option is usable here)
 * `proguardConfig in Android ...` can be used to replace the entire
   proguard config included with android-sdk-plugin
* I have found that Scala applications on android build faster if they're
  using scala 2.8.2. Set the scala version in `build.sbt` by entering
  `scalaVersion := "2.8.2"`
* On-device unit testing, use `android:test` and see
  [Android Testing Fundamentals](http://developer.android.com/tools/testing/testing_android.html)
*  Unit testing with robolectric, see my build.scala for this configuration:
  * https://gist.github.com/pfn/5872909
  * This example is somewhat old and may include settings that are no longer
    necessary, this project hasn't been touched in nearly a year.
  * To get rid of robolectric's warnings about not finding certain classes
    to shadow, change the project target to include google APIs
  * jberkel has written a Suite trait to be able to use robolectric with
    scalatest rather than junit, see https://gist.github.com/2662806
* Device Management
  * The commands `devices` and `device` are implemented. The former lists
    all connected devices. The latter command is for selecting a target
    device if there is more than one device. If there is more than one
    device, and no target is selected, all commands will execute against the
    first device in the list.
  * `android:install`, `android:run` and `android:test` are tasks that can
    be used to install, run and test the built apk on-device, respectively.

### Differences from jberkel/android-plugin ###

Why create a new plugin for building android applications?  Because
`jberkel/android-plugin` is pretty difficult to use, and enforces an
sbt-style project layout. Ease of configuration is a primary objective
of this plugin; configuring a project to use this plugin is 2-3 lines of
configuration plus any standard android project layout, jberkel's requires
the installation of `conscript`, `g8` and cloning a template project which
has lots of autogenerated configuration. This is incompatible with the
built-in SDK configuration and doesn't load up into Eclipse easily. All
android projects, particularly those that have been building using the
standard SDK tools will easily build with this plugin; including regular,
Android Java-based applications.

* This plugin uses the standard Android project layout as created by
  Eclipse and `android create project`. Additionally, it can read all
  existing configuration out of the project's `.properties` files.
* `TR` for typed resources improves upon `TR` in android-plugin. It should be
  compatible with existing applications that use `TR` while also adding a
  `TypedLayout[A]` for layouts. An implicit conversion on `LayoutInflater` to
  `TypedLayoutInflater` allows calling
  `inflater.inflate(TR.layout.foo, container, optionalBoolean)` and receiving
  a properly typed view object.
  * Import `TypedResource._` to get the implicit conversions
* All plugin classes are namespaced under the `android` package

### TODO / Known Issues ###

* Better handling of release vs. debug builds and creating other build
  flavors as supported by the Android Gradle plugin.
* Implement the NDK build process
* Changes to `AndroidManifest.xml` may require the plugin to be reloaded.
  The manifest data is stored internally as read-only data and does not
  reload automatically when it is changed. The current workaround is to
  type `reload` manually anytime `AndroidManifest.xml` is updated if
  necessary. This `reload` is necessary to keep `android:run` working
  properly if activities are changed, and packaging operating correctly
  when package names, or sdk references change.
* sbt `0.12` and `0.13` currently have a bug where jars specified in javac's
  -bootclasspath option forces a full rebuild of all classes everytime. sbt
  `0.12.3` and later has a hack that should workaround this problem. The
  plugin sets the system property `xsbt.skip.cp.lookup` to `true` to bypass
  this issue; this disables certain incremental compilation checks, but should
  not be an issue for the majority of use-cases.

#### Thanks to ####

* pfurla, jberkel, mharrah, retronym and the other folks from #sbt and #scala
  for bearing with my questions and helping me learn sbt.
