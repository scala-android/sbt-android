# Build Android Projects Using SBT #

[![Latest version](https://img.shields.io/bintray/v/pfn/sbt-plugins/sbt-android.svg?maxAge=2592000)](https://bintray.com/pfn/sbt-plugins/sbt-android)
[![Join the chat at https://gitter.im/scala-android/sbt-android](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/scala-android/sbt-android?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

**[Change log](CHANGES.md) |
[FAQ](https://github.com/scala-android/sbt-android/issues?q=label%3AFAQ%20)**

Auto-import from gradle using [sbt-android-gradle](GRADLE.md)

NOTE: 1.6.0 is the last version published using
`addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.6.0")`,
all future updates can be accessed by using
`addSbtPlugin("org.scala-android" % "sbt-android" % VERSION)`


## Description ##

This is an easy-to-use plugin for existing and newly created android
projects. It requires SBT 0.13.8+

The plugin supports all android projects configurations. 3rd party libraries
can be included by placing them in `libs`, or they can be added by using sbt's
`libraryDependencies` feature. This build plugin is 100% compatible with
the standard Android build system due to the use of the same underlying
`com.android.tools.build:builder` implementation.

NOTE: proguard 5.1 does not like old versions of scala. Projects that wish
to use Proguard 5.1 or newer with Scala should use `scalaVersion := "2.11.5"`
or newer. For compatible scala projects and java-based projects which wish to
use proguard 5.1 (to fix issues around generic types being removed from
base-classes) a workaround is to add this setting into your `build.sbt`:
`proguardVersion := "5.1"`.
See [proguard bug #549](https://sourceforge.net/p/proguard/bugs/549/) and
[SI-8931](https://issues.scala-lang.org/browse/SI-8931)

NOTE: support-v4 22.2.x triggers compilation errors, see #173 and
[SI-7741](https://issues.scala-lang.org/browse/SI-7741)

## Support and Help ##

The first line of support is reading this README, beyond that, help can be
found on the #sbt-android IRC channel on Freenode, or the
[scala-android/sbt-android gitter](https://gitter.im/scala-android/sbt-android)

## Example projects ##

* A growing collection of tests can be found under
  [sbt-test/android-sdk-plugin/](sbt-test/android-sdk-plugin).
  These projects are examples of how to use the plugin in various
  configurations.
* Testing the plugin can be run via `sbt scripted`, they require a device
  or emulator to be running in order to pass.
* All tests have auto-generated `build.properties` and `auto_plugins.sbt`
  files that set the current version of sbt and the sbt-android to use
  for testing.

## Usage ##

1. Install sbt (from [scala-sbt.org](http://www.scala-sbt.org) or use your
   local packaging system like macports, brew, etc.)
   * (OPTIONAL) Install the plugin globally by adding the following line
   in the file `~/.sbt/0.13/plugins/android.sbt`:

   ```
   addSbtPlugin("org.scala-android" % "sbt-android" % "1.7.0")
   ```

2. Set the environment variable `ANDROID_HOME` pointing to the path where the
   Android SDK is installed.  If `ANDROID_HOME` is not set, an Android SDK
   will be installed automatically at `~/.android/sbt/sdk`. If any components
   are missing from your SDK, they will be installed automatically.
   * (OPTIONAL) Set `ANDROID_NDK_HOME` if NDK building is desired and an NDK
     already installed. If neither are set, or an NDK is not installed, an
     NDK will be installed to `~/.android/sbt/sdk/ndk-bundle` automatically
     if an NDK build is detected (Android.mk and friends)

3. (N/A if globally configured) Create a directory named `project` within
   your project and add the file `project/plugins.sbt`, in it, add the
   following line:

   ```
   addSbtPlugin("org.scala-android" % "sbt-android" % "1.7.0")
   ```

4. Create a new android project using `gen-android` if the plugin is installed
   globally
   * Instead of creating a new project, one can also do
     `sbt gen-android-sbt` to make sure everything is properly setup
     in an existing project.

5. Create or edit the file named `build.sbt` and add the
   following line, (automatically performed if using `gen-android`) :

   ```
   enablePlugins(AndroidApp)
   ```

6. (OPTIONAL) Select the target platform API you're building against in `build.sbt`,
   if not selected, the newest available will be selected automatically:

   ```
   // for Android 7.0, Nougat, API Level 24:
   platformTarget := "android-24"
   ```

   The Android Developer pages provides a [list of applicable version codes](https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#ApiLevels).

7. Now you will be able to run SBT, some available commands in sbt are:
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
7. If you want `sbt-android` to automatically sign release packages
   add the following lines to `local.properties` (or any file.properties of
   your choice that you will not check in to source control):
   * `key.alias: KEY-ALIAS`
   * `key.alias.password: PASSWORD` (optional, defaults to `key.store.password`)
   * `key.store: /path/to/your/.keystore`
   * `key.store.password: KEYSTORE-PASSWORD`
   * `key.store.type: pkcs12` (optional, defaults to `jks`)

## Advanced Usage ##

* IDE integration
  * The recommended IDE is IntelliJ, not Android Studio. However Android Studio
    can be used with some massaging (i.e install the Scala Plugin).
  * When loading a project into IntelliJ, it is required that the
    [`Android Support`](https://plugins.jetbrains.com/plugin/1792) and `Scala`
    plugins are installed.
  * To ensure proper building, configure the IDE `Run` command to execute an SBT
    `android:package` task instead of `Make` (remove the make entry); this is
    found under `Run Configurations`.
  * The SBT plugin for IntelliJ is the one from
    [orfjackal/idea-sbt-plugin](https://github.com/orfjackal/idea-sbt-plugin).
  * The `Scala` plugin is still required for non-Scala projects in order to
    edit sbt build files from inside the IDE.
  * IntelliJ 14 and newer now includes native support for importing projects
    from `sbt-android`. The process generally works well, however there
    are still several caveats:
    * The `idea-sbt-plugin` is still required to actually perform the build
    * `aar` resources do not show up in editor or autocomplete automatically
      * They can be added manually, but must be added everytime the project
        is refreshed from SBT (SBT toolwindow -> Refresh)
      * To add:
        1. `Project Structure` -> `Modules` -> `+` -> `Import Module`
        2. `$HOME/.android/sbt/exploded-aars/AAR-PACKAGE-FOLDER`
        3. `Create from existing sources`
        4. `Next` all the until to the `Finish` button, finish.
        5. Go to the `Dependencies` tab for the Module you want to be able to
          access the AAR resources, click `+` -> `Module Dependency`
        6. Select the newly added AAR module above, and it will now be visible.
      * Steps 5 and 6 will need to be repeated any time the build description
        is refreshed (SBT toolwindow -> refresh)
      * This has been fixed by
        [JetBrains/sbt-structure#42](https://github.com/JetBrains/sbt-structure/pull/42)
        * Until it gets merged, can download
          [sbt-structure-0.13.jar](sbt-test/android-sdk-plugin/sbt-structure-compatibility/project/lib/sbt-structure-0.13.jar)
          from the test case and place it into
          `$HOME/.IntelliJVERSION/config/plugins/scala/launcher/sbt-structure-0.13.jar`
* Consuming apklib and aar artifacts from other projects
  * Optionally use `apklib()` or `aar()`
    * specifying `apklib()` and `aar()` is only necessary if the pom
      packaging for the dependency is not `apklib` or `aar`
  * `libraryDependencies += aar("groupId" % "artifactId" % "version", "optionalArtifactFilename")`
    * Basically, wrap the typical dependency specification with either
      apklib() or aar() to consume the library
    * If aars or apklibs are duplicately included in a multi-project build,
      specify `transitiveAndroidLibs := false`
    * `apklib` and `aar` that transitively depend on `apklib` and `aar` will
      automatically be processed. To disable set
      `transitiveAndroidLibs := false`
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

* Generating aar artifacts
  * To specify that your project will generate and publish either an `aar`
    artifact simply change the `enablePlugins(AndroidApp)`
    line to `enablePlugins(AndroidLib)`
* Multi-project builds
  * See multi-project build examples in the test cases for an example of
    configuration.
  * All sub-projects in a multi-project build must specify `exportJars := true`.
    Android projects automatically set this variable.
  * When using multi-project builds in Scala, where library projects have
    scala code, but the main project(s) do(es) not, you will need to specify
    that proguard must run. To do this, the following must be set for each
    main project: `proguardScala := true`
* Configuring `sbt-android` by editing `build.sbt`
  * Add configuration options according to the sbt style:
    * `useProguard := true` to enable proguard. Note: if you
      disable proguard for scala, you *must* specify uses-library on a
      pre-installed scala lib on-device or enable multi-dex.
  * Configurable keys can be discovered by typing `android:<tab>` at the
    sbt shell
* Configuring proguard, some options are available
  * `proguardOptions ++= Seq("-keep class com.foo.bar.Baz")` -
    will tell proguard not to obfuscute nor optimize code (any valid proguard
    option is usable here)
 * `proguardConfig ...` can be used to replace the entire
   proguard config included with sbt-android
   * To allow obfuscation: `proguardConfig -= "-dontobfuscate"`
   * To allow optimization: `proguardConfig -= "-dontoptimize"`
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
* Full list of `sbt-android` added commands, all commands have full tab
  completion when possible.
  * `adb-ls <path>`
  * `adb-cat <file>`
  * `adb-rm <file>`
  * `adb-pull <file> [destination]`
  * `adb-push <file> <destination>`
  * `adb-shell <command>`
  * `adb-runas <command>`
  * `adb-kill[/project]`
  * `logcat [-p pid] [-s tags] [options...]`
  * `logcat-grep [-p pid] [regex]`
  * `pidcat[/project] [partial pkg] [TAGs...]`
  * `pidcat-grep[/project] [partial pkg] [regex]`
  * `gen-android <package> <name>`
  * `gen-android-sbt`
  * `device <serial>`
  * `devices`
  * `adb-screenon`
  * `adb-wifi`
  * `adb-reboot [recovery|bootloader]`
  * `variant[/project] [buildType] [flavor]`
  * `variant-reset[/project]`
  * `android-install <package>`
  * `android-update <all|package>`
  * `android-license <license-id>`

### TODO / Known Issues ###

* `autolibs` do not properly process `aar` resources. If anything
  in an `autolib` uses resources from such a library, the answer is to create
  a standard multi-project build configuration rather than utilize `autolibs`.
  `autolibs` can be disabled by manually configuring `localProjects`
* androidTest cannot be written in scala if one wants to use junit4 annotations.
  a workaround is possible if setting `minSdkVersion` to `21` is ok. With
  minSdk set to 21, also set `dexMulti := true` and
  `useProguardInDebug := false` to bypass proguard. This will allow junit4
  tests written in scala to function.
