# Android SDK Plugin for SBT #

Current version is 1.3.15

## Description ##

This is an easy-to-use plugin for existing and newly created android
projects.  It is tested and developed against 0.13.5+.

The plugin supports normal android projects and projects that reference
library projects. 3rd party libraries can be included by placing them in
`libs` as in regular projects, or they can be added by using sbt's
`libraryDependencies` feature.

NOTE: proguard 5.1 does not like all current versions of scala. for java-based
projects which wish to use proguard 5.1 (to fix issues around generic types
being removed from base-classes) a workaround is to add a local file,
`project/proguard.sbt`, containing
`libraryDependencies += "net.sf.proguard" % "proguard-base" % "5.1"`.
Should be fixed in Scala `2.11.5`. See
[proguard bug #549](https://sourceforge.net/p/proguard/bugs/549/) and
[SI-8931](https://issues.scala-lang.org/browse/SI-8931)

## New features in 1.3.x ##

* `1.3.15`:
  * Update checker to notify of new versions
  * Fix multi-project retrolambda build issues
  * Fix `adb-wifi` to use `adb tcpip` internally
* `1.3.14`:
  * Support for [Retrolambda](https://github.com/orfjackal/retrolambda), Java8
    lambda syntax.
    * Automatically enabled when JDK 8 and java sources are detected.
    * Manually enable by setting `retrolambdaEnable in Android := true` (or,
      conversely, `false` to disable if it was automatically enabled)
    * Sample in
      [hello-multidex test case](sbt-test/android-sdk-plugin/simple-retrolambda)
* `1.3.13`:
  * Update to release builder `1.0.0`
  * Attempt to fix proguard-cache delta bug
* `1.3.12`:
  * update to latest android builder `1.0.0-rc1`
  * fix javah bug #131
  * fix double-tab crash #130
  * split `test` from `android:test` (better support for robolectric)
  * manifest placeholders, set `manifestPlaceholders in Android`
    * `Map[String,String]` for `key:value`s to replace in AndroidManifest.xml
    * Placeholders are expanded using `${key}` syntax
    * Can be dynamically configured as it is implemented as an SBT task
* `1.3.11`:
  * multidex support (thank you @dant3) see
    [the android reference documentation](https://developer.android.com/reference/android/support/multidex/MultiDex.html)
    and the
   [hello-multidex test case](sbt-test/android-sdk-plugin/hello-multidex)
    for an example of usage
  * `adb-runas` command: run a command as the current development package user
* `1.3.10`:
  * `adb-kill` command: kill the currently running package process
    (if not foreground)
  * update android builder (0.14.2), proguard (5.0) and asm dependencies (5.0)
  * `1.3.7`, `1.3.8` and `1.3.9` are bad releases, moderate bugs
* `1.3.5`:
  * Last release for sbt `0.12.x`
  * unseal ProjectLayout
  * allow proguard-cache on java-only projects
  * `adb-screenon` command (turn screen of device on/unlock)
  * include renderscript generated resources in aar
* `1.3.4`: bugfixes
  * #81 add fullClasspath to javah
  * update to builder 0.12.2
  * #82 add NDK_PROJECT_PATH environment for ndkbuild
  * #84 package dependsOn managedResources
  * #85 sourceManaged = gen
  * minor ndk build fixes (apkbuild depends on *.so)
* `1.3.3`: Add `ApkSigningConfig`, `PlainSigningConfig`,
  `PromptStorepassSigningConfig` and `PromptPasswordsSigningConfig`. These
   various signing configurations allow control over prompting for keystore
   and key passwords. The default is `PlainSigningConfig` which observes the
   original behavior from ant builds (reads properties out of
   `local.properties`). Set `apkSigningConfig in Android` to one of these
   variants to perform non-default behavior.
   * Also added `androidBuildWith()` project decorator, replaces
    `androidBuild(projects)` and `dependsOn(projects)`
* `1.3.2`: add `AutoPlugin` support for `0.13.5`
  * Auto-set `localProjects` when using `android.Plugin.androidBuild(...)`
  * When `gen-android`, `gen-android`, and `android.AutoBuild` require `0.13.5`
    if on the `0.13.x` sbt line.
  * Some refactoring of classes out of `android.Keys`, should be mostly
    compatible still.
* `1.3.1`: add `android:apkbuild-pickfirsts` works like
  `android:apkbuild-excludes` but picks the first occurrence of the resource.
  * A bug in com.android.tools.build:builder,
    [android bug #73437](https://code.google.com/p/android/issues/detail?id=73437),
    prevents PackagingOptions from working correctly with JNI libraries.
  A workaround is implemented copy all JNI to a single location first.
* NDK build process, similarly to `ANDROID_HOME`, set `ANDROID_NDK_HOME` to
  the location where the Android NDK is installed. Alternatively, `ndk.dir`
  can be set in a `local.properties` file for the project.
  * libs will be generated into `binPath / "jni"` and
    obj will drop into `binPath / "obj"`
  * Pre-generated JNI libraries will no longer be pulled out of `jni`
    (nor `src/main/jni`) -- they will be taken from `libs` (or `src/main/libs`)
    * This does not apply to aar and apklib--they will be pulled out of
      appropriate locations per their spec.
  * `javah` is automatically executed on all classes that have `native` methods
    in their signatures. The header files are generated into `sourceManaged`
    and are available to include in native sources and `Android.mk` by adding
    `LOCAL_CFLAGS := -I$(SBT_SOURCE_MANAGED)`
  * `collect-jni` no longer copies libraries, it only assembles a list of
    directory names for packaging
* Global plugin installation friendly
  * For sbt 0.13, add to `~/.sbt/0.13/plugins/android.sbt`
  * For sbt 0.12, add to `~/.sbt/plugins/android.sbt`
  * `addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.3.15")`
* New commands, all commands have proper tab-completion:
  * `gen-android` - creates android projects from scratch with sbt plumbing
  * `gen-android-sbt` - creates SBT files for an existing android project
  * `logcat` - supports all regular options, non-polling (-d by default)
  * `pidcat` - logcat the current package or specified package with TAG filters
  * `adb-ls` - ls on-device
  * `adb-cat` - cat a file on-device
  * `adb-rm` - rm a file on-device
  * `adb-shell` - execute a shell command on-device
  * `adb-push` - push a file to device
  * `adb-pull` - pull a file from device
  * `reboot-device` renamed to `adb-reboot`
* Existing commands available globally
  * `devices`, `device`, `adb-wifi`
* `AutoBuild` support, (created automatically with `gen-android`), set your
  build to be `object Build extends android.AutoBuild` and settings will be
  automatically applied to projects as necessary.
* Update to latest `com.android.tools.build` `0.12.x`
  * Now requires android build-tools `19.1.0` or newer
* `minSdkVersion` and `targetSdkVersion` are now `SettingKey[String]` and no
  longer `SettingKey[Int]` (support android-L)
* Instrumentation tests are now located in `src/main/androidTest` instead of
  `src/main/instrumentTest` (match layout generated by android create project)
* `android:dex` task now returns a folder for the output dex not a `classes.dex`
  file.

## New features in 1.2.x (last version: 1.2.20) ##

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
* `1.2.18`: `zipalignPath` has changed from a Setting into a Task

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

* Add a better method of specifying local-projects besides only in
  project.properties, or overriding library-projects in a convoluted manner.
  use `localProjects in Android += android.Dependencies.LibraryProject(lib_project.base)`
  settings to add library projects without declaring them in
  `project.properties` or otherwise
* Add `local-aars` setting to allow the use of AARs without a repo.
* Add `android.ArbitraryProject` load any project you want from a git repo,
  see [this example](https://gist.github.com/pfn/6238004) for details.

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
   * (OPTIONAL) Install the plugin globally into `~/.sbt/plugins` or
    `~/.sbt/0.13/plugins` (for 0.12 and 0.13, respectively)
    
   ```
   addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.3.15")
   ```
   
2. Create a new android project using `gen-android` if the plugin is installed
   globally
   * Instead of creating a new project, one can also do
     `android update project` to make sure everything is properly setup
     in an existing project.
   * Instead of keeping local.properties up-to-date, you may set the
     environment variable `ANDROID_HOME` pointing to the path where the
     Android SDK is unpacked. This will bypass the requirement of having
     to run `android update project` on existing projects.
   * When using `gen-android`, the `platformTarget` is automatically set to
     the newest version available in your local SDK, override this by setting
     `target` in a `project.properties` file, or setting
     `platformTarget in Android`
3. (N/A if globally configured) Create a directory named `project` within
   your project and add the file `project/plugins.sbt`, in it, add the
   following line:

   ```
   addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.3.15")
   ```

4. Create a file named `project/build.scala` and add the
   following line, (automatically performed if using `gen-android`) :
   
   ```
   object Build extends android.AutoBuild
   ```

5. Now you will be able to run SBT, some available commands in sbt are:
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
6. If you want android-sdk-plugin to automatically sign release packages
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
    `addSbtPlugin("com.hanhuy.sbt" % "sbt-idea" % "1.7.0-SNAPSHOT")` to your
    `project/plugins.sbt` and running the command `sbt gen-idea`
    * This requires the snapshots repo which can be done by adding
      `resolvers += Resolver.sbtPluginRepo("snapshots")`
    * Use my snapshot of sbt-idea until mpeltonen/sbt-idea#314 is merged
    * As with this plugin, sbt-idea may be installed globally as well.
  * When loading a project into IntelliJ, it is required that the `SBT`
    and `Scala` plugins are installed; the `SBT` plugin allows replacing the
    default `Make` builder with sbt, enabling seamless builds from the IDE.
  * The best practice is to set the IDE's run task to invoke sbt
    `android:package` instead of `Make`; this is found under the Run
    Configurations
  * The SBT plugin for IntelliJ is the one from
    [orfjackal/idea-sbt-plugin](https://github.com/orfjackal/idea-sbt-plugin)
  * The `Scala` plugin is still required for non-Scala projects in order to
    edit sbt build files from inside the IDE.
  * Instead of using `sbt-idea`, IntelliJ 14 now includes native support for
    importing projects from `android-sdk-plugin`. The process generally works
    well, however there are still several caveats:
    * The `idea-sbt-plugin` is still required to actually perform the build
    * `classDirectory in Compile` is not automatically included as a library,
      as a result apklib classes will not resolve unless it is added manually
      (`bin/classes` or `target/android-bin/classes`) as a library.
      [SCL-7973](https://youtrack.jetbrains.com/issue/SCL-7973)
    * Paths are incorrect on Windows
      [SCL-7908](https://youtrack.jetbrains.com/issue/SCL-7908)
    * Gradle-style layouts still aren't fully supported (resources won't
      resolve in the IDE)
      [SCL-6273](https://youtrack.jetbrains.com/issue/SCL-6273)
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
      "com.google.android.gms" % "play-services" % "4.4.52"
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
  * Multi-project builds *must* specify
    `transitiveAndroidLibs in Android := false` if any of the subprojects
    include `aar`s or `apklib`s as dependencies.
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
* On-device unit testing, use `android:test` and see
  [Android Testing Fundamentals](http://developer.android.com/tools/testing/testing_android.html)
*  Unit testing with robolectric and Junit (use the `test` task), see how
   it works in the
   [robo-junit-test test case](sbt-test/android-sdk-plugin/robo-junit-test)
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

* Changing usage of `implicit`s (defs, vals, classes, etc.) confuses
  the proguard cache. It results in `NoSuchMethodError`s even though present
  in the generated dex. Current workaround is to `clean` when `implicit`
  usage changes occur.
* Version checking of plugin and update notifications. This is not possible
  with ivy. Options: relocate plugin to sonatype and/or host an off-site
  versions config descriptor.
* Better handling of release vs. debug builds and creating other build
  flavors as supported by the Android Gradle plugin.
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
* `autolibs` do not properly process `apklib` and `aar` resources. If anything
  in an `autolib` uses resources from such a library, the answer is to create
  a standard multi-project build configuration rather than utilize `autolibs`.
  `autolibs` can be disabled by manually configuring `localProjects in Android`
