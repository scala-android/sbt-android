# Android SDK Plugin for SBT #

[![Join the chat at https://gitter.im/pfn/android-sdk-plugin](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/pfn/android-sdk-plugin?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Current version is 1.5.10 ([Change log](CHANGES.md))

Auto-import from gradle using [android-gradle-build](GRADLE.md)

## Description ##

This is an easy-to-use plugin for existing and newly created android
projects.  It is tested and developed against 0.13.6+.

The plugin supports normal android projects and projects that reference
library projects. 3rd party libraries can be included by placing them in
`libs` as in regular projects, or they can be added by using sbt's
`libraryDependencies` feature. This build setup is 100% compatible with
Google's own `gradle` build system due to the use of the same underlying
`com.android.tools.build:builder` implementation.

NOTE: proguard 5.1 does not like old versions of scala. Projects that wish
to use Proguard 5.1 and Scala should use `scalaVersion := "2.11.5"` or newer.
For compatible scala projects and java-based projects which wish to use
proguard 5.1 (to fix issues around generic types being removed from
base-classes) a workaround is to add a local file, `project/proguard.sbt`,
containing:
`libraryDependencies += "net.sf.proguard" % "proguard-base" % "5.1"`.
See [proguard bug #549](https://sourceforge.net/p/proguard/bugs/549/) and
[SI-8931](https://issues.scala-lang.org/browse/SI-8931)

NOTE: support-v4 22.2.x triggers compilation errors, see #173 and
[SI-7741](https://issues.scala-lang.org/browse/SI-7741)

## Support and Help ##

The first line of support is reading this README, beyond that, help can be
found on the #sbt-android IRC channel on Freenode

## Example projects ##

* A variety of my own projects can be found on github that use this plugin
* In addition to this, a growing collection of tests can be found under
  [sbt-test/android-sdk-plugin/](sbt-test/android-sdk-plugin).
  These projects are examples of how to use the plugin in various
  configurations.
* Tests can be run via `sbt scripted`, they require `ANDROID_HOME` and
  `ANDROID_NDK_HOME` to be set in addition to having platform `android-17`
  installed.
* All tests have auto-generated `build.properties` and `auto_plugins.sbt`
  files that set the current version of sbt and the android-sdk-plugin to use
  for testing.

## Usage ##

1. Install sbt (from [scala-sbt.org](http://www.scala-sbt.org) or use your
   local packaging system like macports, brew, etc.) -- make sure the
   Android SDK is fully updated (minimum build-tools 19.1.0 and up)
   * (OPTIONAL) Install the plugin globally by adding the following line
   in the file `~/.sbt/0.13/plugins/android.sbt`:
    
   ```
   addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.5.10")
   ```
   
2 Set the environment variable `ANDROID_HOME` pointing to the path where the
     Android SDK is unpacked.
3. Create a new android project using `gen-android` if the plugin is installed
   globally
   * Instead of creating a new project, one can also do
     `sbt gen-android-sbt` to make sure everything is properly setup
     in an existing project.
   * When using `gen-android`, the `platformTarget` is automatically set to
     the newest version available in your local SDK, override this by setting
     `target` in a `project.properties` file, or setting
     `platformTarget in Android`
4. (N/A if globally configured) Create a directory named `project` within
   your project and add the file `project/plugins.sbt`, in it, add the
   following line:

   ```
   addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.5.10")
   ```

5. Create a file named `project/build.scala` and add the
   following line, (automatically performed if using `gen-android`) :
   
   ```
   object Build extends android.AutoBuild
   ```

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
   * `android:test`
     * run instrumented android unit tests
   * `android:install`
     * Install the application to device
   * `android:run`
     * Install and run the application on-device
   * `android:uninstall`
     * Uninstall the application from device
   * Any task can be repeated continuously whenever any source code changes
     by prefixing the command with a `~`. `~ android:package-debug`
     will continuously build a debug build any time one of the project's
     source files is modified.
7. If you want android-sdk-plugin to automatically sign release packages
   add the following lines to `local.properties` (or any file.properties of
   your choice that you will not check in to source control):
   * `key.alias: KEY-ALIAS`
   * `key.alias.password: PASSWORD` (optional, defaults to `key.store.password`)
   * `key.store: /path/to/your/.keystore`
   * `key.store.password: KEYSTORE-PASSWORD`
   * `key.store.type: pkcs12` (optional, defaults to `jks`)

## Advanced Usage ##

* IDE integration
  * The primary IDE recommendation is IntelliJ, not Android Studio
    nor Eclipse.
  * When loading a project into IntelliJ, it is required that the `Android`
    and `Scala` plugins are installed
  * The best practice is to set the IDE's run task to invoke sbt
    `android:package` instead of `Make`; this is found under the Run
    Configurations
  * The SBT plugin for IntelliJ is the one from
    [orfjackal/idea-sbt-plugin](https://github.com/orfjackal/idea-sbt-plugin)
  * The `Scala` plugin is still required for non-Scala projects in order to
    edit sbt build files from inside the IDE.
  * IntelliJ 14 now includes native support for importing projects from
    `android-sdk-plugin`. The process generally works well, however there
    are still several caveats:
    * Android configurations will not load properly until
      `Scala plugin 1.5.4` is released
    * The `idea-sbt-plugin` is still required to actually perform the build
    * `classDirectory in Compile` is not automatically included as a library,
      as a result apklib classes will not resolve unless it is added manually
      (`bin/android/intermediates/classes` or
      `target/android/intermediates/classes`) as a library.
      [SCL-7973](https://youtrack.jetbrains.com/issue/SCL-7973)
* Consuming apklib and aar artifacts from other projects
  * Optionally use `apklib()` or `aar()`
    * using `apklib()` and `aar()` are only necessary if there are multiple
      filetypes for the dependency, such as `jar`, etc.
  * `libraryDependencies += apklib("groupId" % "artifactId" % "version", "optionalArtifactFilename")`
    * Basically, wrap the typical dependency specification with either
      apklib() or aar() to consume the library
    * If aars or apklibs are duplicately included in a multi-project build,
      specify `transitiveAndroidLibs in Android := false`
    * `apklib` and `aar` that transitively depend on `apklib` and `aar` will
      automatically be processed. To disable set
      `transitiveAndroidLibs in Android := false`
  * Sometimes library projects and apklibs will incorrectly bundle
    android-support-v4.jar, to rectify this, add this setting, repeat for any
    other incorrectly added jars:
    ```
    unmanagedJars in Compile ~= {
      _ filterNot (_.data.getName startsWith "android-support-v4")
    }
    ```
* Using the google gms play-services aar:

    ```
    libraryDependencies +=
      "com.google.android.gms" % "play-services" % "VERSION"
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
  * See multi-project build examples in the test cases for an example of
    configuration.
  * `androidBuild(...)` should be used to specify all dependent library-projects
  * All sub-projects in a multi-project build must specify `exportJars := true`.
    Android projects automatically set this variable.
  * When using multi-project builds in Scala, where library projects have
    scala code, but the main project(s) do(es) not, you will need to specify
    that proguard must run. To do this, the following must be set for each
    main project: `proguardScala in Android := true`
* Configuring `android-sdk-plugin` by editing build.sbt
  * `import android.Keys._` at the top to make sure you can use the plugin's
    configuration options (not required with sbt 0.13.5+ and AutoPlugin)
  * Add configuration options according to the sbt style:
    * `useProguard in Android := true` to enable proguard. Note: if you
      disable proguard for scala, you *must* specify uses-library on a
      pre-installed scala lib on-device or enable multi-dex.
  * Configurable keys can be discovered by typing `android:<tab>` at the
    sbt shell
* Configuring proguard, some options are available
  * `proguardOptions in Android += Seq("-dontobfuscate", "-dontoptimize")` -
    will tell proguard not to obfuscute nor optimize code (any valid proguard
    option is usable here)
 * `proguardConfig in Android ...` can be used to replace the entire
   proguard config included with android-sdk-plugin
* On-device testing, use `android:test` and see
  [Android Testing Fundamentals](http://developer.android.com/tools/testing/testing_android.html)
*  Unit testing with robolectric and Junit (use the `test` task), see how
   it works in the
   [robo-junit-test test case](sbt-test/android-sdk-plugin/robo-junit-test)
   * Be sure to set `fork in Test := true` otherwise the classloading black
     magic in robolectric will fail.
* Device Management
  * The commands `devices` and `device` are implemented. The former lists
    all connected devices. The latter command is for selecting a target
    device if there is more than one device. If there is more than one
    device, and no target is selected, all commands will execute against the
    first device in the list.
  * `android:install`, `android:run` and `android:test` are tasks that can
    be used to install, run and test the built apk on-device, respectively.
  * Type `help` for a list of all available commands.

### TODO / Known Issues ###

* `autolibs` do not properly process `apklib` and `aar` resources. If anything
  in an `autolib` uses resources from such a library, the answer is to create
  a standard multi-project build configuration rather than utilize `autolibs`.
  `autolibs` can be disabled by manually configuring `localProjects in Android`
