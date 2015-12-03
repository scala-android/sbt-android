## Changes in 1.5.x ##

* `1.5.10`:
  * add `-p` option to `logcat` command to filter by pid
  * also include jars when searching for jni libraries to import (regression fix)
  * fix missing res apk when toggling debug/release builds (regression fix)
* `1.5.9`:
  * Update to android builder `1.5.0`
  * Fix TypedLayoutInflater when inflating into a container
  * Add flavor+buildType source directories/manifest overlays automatically
  * Add `apkDebugSigningConfig`, allows custom debug signing configuration
  * support for rendering `VectorDrawable` to `png` for pre-lollipop, place
    `VectorDrawable`s into `res/drawable` and they will be converted to
    `mdpi`, `hdpi`, `xhdpi` and `xxhdpi` rasters automatically when
    `minSdkVersion` is below 21. Do not put vectors into `drawable-anydpi-v21`,
    those will not get rasterized automatically.
    * To clear the lint errors from using `<vector>` images, add this line into
      lint.xml:
      `<issue id="NewApi"><ignore path="src/main/res/drawable"/></issue>`
  * Can now properly build [u2020](https://github.com/JakeWharton/u2020)
    automatically using `android-gradle-build` and a few settings in `build.sbt`:
    * `retrolambdaEnabled := true`
    * `libraryDependencies += "com.squareup.dagger" % "dagger-compiler" % "1.2.2" % "provided"`
    * `android.dsl.apkExclude("META-INF/services/javax.annotation.processing.Processor")`
* `1.5.8`: minor bugfix release
* `1.5.7`:
  * fix `retrolambda-enable` to `retrolambda-enabled` in repl
  * re-enable `copyResources`
  * Fix issue with `RootProject`s and `dependsOn(androidSubProject)` checking
  * Honor `android:outputLayout` in more places (everywhere?)
  * bring back `FileFunction.cached` for android res on a best-effort basis
  * `android:use-proguard` and `android:use-proguard-in-debug` no longer depend
    on each other
* `1.5.6`:
  * Fix `logcat`, allow passing arguments
  * Load flavor, buildType, applicationId, versionName and code into
    BuildConfig automatically
  * No longer de-duplicate JNI libraries automatically, this must be managed
    using packagingOptions
  * Warn when using `dependsOn(androidSubProject)` incorrectly
* `1.5.5`:
  * warn when aar dependency versions are improperly configured (thanks @tek)
  * colorize `logcat`, force `-v brief` for marshmallow compatibility (fixes
    pidcat for marshmallow as well)
  * cache all common library predexing into `$HOME/.android/predex`;
    project-specific libraries, including all aars, remain in
    `target/intermediates/predex`
  * changed all `android.Keys._` to automatically be `in Android`. It is no
    longer necessary to specify `in Android` when configuring android settings
    * If re-using these keys from another configuration using
      `inConfig(CONFIG)`, then they will need to be explicitly set as
      `androidKey in CONFIG`. Should not affect normal usage.
* `1.5.4`: update checker fix
* `1.5.3`:
  * Fix bug when there are 2 android sub-projects and using a command that
    accepts the `/PROJECT` option (would select the incorrect project)
  * Start work on an experimental `android.dsl` API
* `1.5.2`:
  * Add an entirely new, alternative, `flavors` and `buildTypes` system.
    * Configure by adding `buildTypes in Android += (("name", List(settings)))`
      or the same for `flavors in Android`.
    * Load the variant configuration by using the
      `variant[/PROJECT] [BUILD_TYPE] [FLAVOR]` command, run `variant[/PROJECT]`
       by itself to see current variant status and available flavors and/or
       build types.
      * Alternatively, load a default variant by adding
       `android.Plugin.withVariant("PROJECT-ID", Option("BUILD-TYPE"), Option("FLAVOR"))`
       into `build.sbt`
    * LIMITATIONS: Any `set` command must occur prior to calling `variant` or
      else the `set` command will reset all variant settings
  * warn if `exportJars` is set incorrectly on dependency projects
  * automatically set project for `pidcat`, `adb-runas`, `adb-kill` when
    possible. Also allow appending `/<project>` to any of those commands
    * `adb-kill` now uses `am force-stop` when running against a device api 11+
* `1.5.1`:
  * add `android:debug` command to wait for debugger to connect on app launch
  * properly include assets from transitive aar and apklibs
  * change `androidBuildWith` and `buildWith` to accept `ProjectReference`
    arguments instead of `Project`
  * build optimizations for multi-project builds (more transitive library fixes,
    thanks @tek)
  * ensure consistent usage of `useProguardInDebug`, `proguardScala`, etc.
    * fix `typedResources` to default to the value of `autoScalaLibrary` not
      `proguardScala`
  * turn `lintFlags` into a task, rather than setting
  * add `predexSkip` defaults to all local library projects, defines jars
    which should be included in main dex (for sharding, etc)
* `1.5.0`:
  * build outputs completely refactored, `genPath`, `binPath`, and other
    settings have been removed; outputs are completely configurable by setting
    `outputLayout in Android` to a function `ProjectLayout => BuildOutput`
    ```
    outputLayout in Android := {
      val base = (outputLayout in Android).value
      (p: ProjectLayout) => new android.BuildOutput.Wrapped(base(p)) {
        // example: changes default from "target/android/intermediates"
        // to "build/steps"
        override def intermediates = p.base / "build" / "steps"
      }
    }
    ```
    * Project flavor build outputs also updated (no longer go into
      `flavor-target`, instead just `flavor/`), no longer work in conjunction
      with `android.AutoBuild`, `androidBuild` must be explicitly set when
      working with flavors.
  * `apkbuildExcludes` and `apkbuildPickFirsts` have been removed,
    use `packagingOptions in Android` in conjunction with the
    `PackagingOptions` object
  * Renamed `dexMainFileClasses`, `dexMinimizeMainFile`, and
    `dexMainFileClassesConfig` to `dexMainClasses`, `dexMinimizeMain`,
    and `dexMainClassesConfig`, respectively.
  * Renamed `retrolambdaEnable` to `retrolambdaEnabled`
  * Add `extraAssetDirectories`
  * Dex sharding with `minSdkVersion` 21 or higher, dramatically improves
    incremental build times, enable by setting `dexShards in Android := true`
    * Add `dexLegacyMode` to set when predexing optimizations and sharding are
      disabled; automatically true when `minSdkVersion` < 21
    * Disabled parallel dx from `1.4.14`, it creates too much of a cpu/memory load
  * Add `inProjectScope(project)(settings...)` to make it easier to configure
    multi-project builds

## New features in 1.4.x (last version: 1.4.15) ##

* `1.4.14`:
  * Multi-project multi-dex fixes for OSX
  * Parallelized pre-dexing
* `1.4.13`:
  * `android:install` and `android:uninstall` now respect `android:allDevices`
  * add `android:clean` for clearing app data from device
* `1.4.11`:
  * include `aars` when generating `proguardConfig`
  * fix `mainDexClasses` on non-Windows platforms
  * implement `android.GradleBuild` in `"com.hanhuy.sbt" % "android-gradle-build" % "0.2"`
    * Automatic building from gradle projects without having to configure SBT
    * See the [gradle-build test cases](sbt-test/gradle-build)
      for an example of usage
    * Known issue: transitive aar libraries that are specified in both library
      and app modules will fail to build
  * use `AutoPlugin` triggers
* `1.4.10`:
  * Set `autoScalaLibrary` based on presence of scala sources
  * Set `minSdkVersion` and `targetSdkVersion` based on `platformTarget`
    unless explicitly specified in `AndroidManifest.xml`, previously defaulted
    to 1
  * Properly skip `dex` if files unchanged.
  * `retrolambdaEnable` is set false by default
  * More refactoring to support Protify, see
    [live-code demo](https://youtu.be/4MaGxkqopII)
* `1.4.9`:
  * add color to pid in `pidcat`
  * fix flavor target directory when flavoring a subproject
  * suppress lint error in androidBuildJar
  * fix `javacOptions in doc`
  * turn update checker into `updateCheck in Android` which can be set to `()`
    to squelch
  * refactoring to support [Protify](https://github.com/pfn/protify), see demos
    [Protify Layout Prototyper Demo](https://www.youtube.com/watch?v=sgT9RA4SONU)
    and [Protify Code Prototyper Demo](https://youtu.be/g63I87UZ6bg?t=3m10s)
* `1.4.8`:
  * Update to new android gradle/builder 1.3.0
    * add `libraryRequests` and `packagingOptions` settings
  * warn about performance when using generated `maindexlist.txt`
  * add `androidBuildJar` and `buildJar` for creating jar libraries without
    resources (anytime aar or apklib is not required).
  * automatically cleanup resources when aar/apklib dependencies are changed,
    no longer requires clean build after changes.
* `1.4.7`:
  * proguard + cache improvements:
    * no longer need to clean after updating proguard and/or cache rules
  * add `android:allDevices` for automatically executing `android:install`,
    `android:run`, and/or `android:test` against all connected devices
  * lots of code cleanup (remove bad uses of `sbt.State`
  * improve the output of `devices` (include api level and battery status)
  * move the output of `AndroidBuilder` into `debug`
  * re-expose `sdkPath`, also add `ndkPath` as a setting
  * fix `watchSources` to properly compile on resource, jni, ndk, etc. changes
  * deprecate `androidBuildApklib`, should use `androidBuildAar` instead
  * fail the build when trying to run library projects
  * automatically generate `maindexlist.txt` if `dexMainFileClasses in Android`
    is not set
* `1.4.6`:
  * rename `android:packageName` to `android:applicationId`,
    uses `android:packageName` if set, otherwise falls back to value in
    `android:manifest`. `android:packageName` is reverted to be a
    `SettingKey` to fix collision with `sbt-native-packager`; fixes #178
  * add `resValues in Android`; allow specifying res/values from build (for
    flavors and auto-generated resources); analogous to `resValue` from the
    gradle android plugin.
* `1.4.5`:
  * colorize `pidcat` command
  * fix several proguard cache related bugs (minor)
  * organize predex bin output better
* `1.4.4`:
  * minor NDK build improvements
  * add testSources to watchSources (automatically trigger ~ commands)
* `1.4.3`:
  * `logcat` and `pidcat` performance improvements
  * minor signing config fixes (fix clobbering of settings, only prompt
    passwords once)
  * Instrumentation testing: do not squelch `testAndroidTestCaseSetUpProperly`
* `1.4.2`:
  * multidex updates
    * Allow proguard/proguardCache in conjunction with multidex
    * Implement minSdkVersion=21+ multidex optimizations (dramatically improve
      incremental build times when using multidex)
* `1.4.1`:
  * add a dex method counter, spits out number of methods in all dex files,
    aids in deciding when to add additional proguard-cache rules, and switching
    to/from multi-dex
  * Enhanced proguard-cache jar processing, no longer messes up conflicting
    case-sensitive filenames (can now proguard-cache obfuscated jars)
  * Fix apklibs source generators when using build flavors
* `1.4.0`:
  * This version **is not entirely backward compatible** with 1.3.x; `TR.scala`
    and `proguardCache` have undergone significant changes.
  * Some code re-organization, internal settings hidden from public view (can
    still be accessed by defining SettingKey and TaskKey manually as required)
  * Add `android:bootClasspath` for use with robolectric
  * Unused resource shrinker, enable with `shrinkResources in Android := true`
    * Only runs if proguard is run, typically used for clean, release builds.
    * See the
      [resource shrinking documentation](http://tools.android.com/tech-docs/new-build-system/resource-shrinking)
  * Conversion of some settings to tasks: `packageName`, `manifest`,
    `packageForR`, `versionName`, `versionCode`, `minSdkVersion`,
    `targetSdkVersion`
    * See all [available keys](src/keys.scala)
  * TypedResource improvements, now uses value class extensions for runtime
    performance improvements (all apps must now use scala 2.10+)
    * Renamed `TypedViewHolder` in favor of a single `TypedFindView`,
      removed `TypedView`, `TypedActivity`, `TypedActivityHolder`, `TypedDialog`
  * Proguard cache improvements, no more `ProguardCache` DSL, instead, add
    package prefix strings to cache directly to `proguardCache in Android`
    (`Seq[String]`)
  * Add `extraResDirectories` setting for additional overlay-resources. For use
    with build flavors, etc.

## New features in 1.3.x (last version: 1.3.24) ##

* `1.3.24`:
  * Minor lint fix (honor min/target sdk from build file)
  * Last version to support scala 2.8.x and 2.9.x
* `1.3.23`:
  * Remove repeated lint output
  * Fix `android.Plugin.flavorOf`
    * Demonstrate usage of `flavorOf` along with junit4 instrumented testing
      in the [android-test-kit test case](sbt-test/android-sdk-plugin/android-test-kit)
  * Fix doubled update checker message
  * Update `com.android.tools.build` and `lint` dependencies
* `1.3.22`:
  * Initial lint support
    * Configured to only detect API level issues by default
    * New setting keys:
      * `android:lintEnabled`: run lint in `compile`, default true
      * `android:lintFlags`: optional flags for lint
      * `android:lintStrict`: fail the build on lint errors, default false
      * `android:lintDetectors`: lint rules to detect, default: API level issues
    * New task `android:lint`: run lint independently of compile, will not run
      compile before-hand, otherwise, behaves according to settings above.
    * Available lint detectors can be found documented at
      http://www.javadoc.io/doc/com.android.tools.lint/lint-checks/24.2.2
  * Update to `com.android.tools.build:builder:1.2.0`
  * Remove stack traces on build failures (stack traces for errors only)
* `1.3.21`:
  * proguard-cache regression fix, force non-incremental dex on first cache-hit
  * remove bad ProguardCache() overloads
* `1.3.20`:
  * Fixes for proguardCache and aars
  * No longer deduplicate jars based on name (
    filter using `dependencyClasspath` instead if necessary)
* `1.3.19`:
  * Bug fixes around retrolambda, `gen-android`, and other minor issues
  * Update to builder 1.1.3
* `1.3.18`:
  * Initial implementation of `android.Plugin.flavorOf` for build flavors.
    * Simple usage is `lazy val flavorproject = android.Plugin.flavorOf(baseproject,
     "flavor-name", flavorSettings /* copies and override baseproject settings */)`
    * The `flavorproject` is otherwise a normal sbt project and it can be treated
      as such.
* `1.3.17`:
  * Reimplemented `renderscript` task, thanks @zbsz
  * Update checker fixes
  * Fork the retrolambda process for google play services failures
  * Update to builder `1.1.1`
  * Error on ambiguous project layouts
  * Add `android:buildConfigOptions` for customizing `BuildConfig.java`
  * Add `android:apkbuildDebug` to replace `createDebug` global variable
* `1.3.16`:
  * Add `android:test-only` thanks @tek
  * Fix `gen-android` and `gen-android-sbt` to create `android.sbt` with the
    current plugin version
  * Update to builder `1.0.1`
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
      [simple-retrolambda test case](sbt-test/android-sdk-plugin/simple-retrolambda)
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
  * `addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.4.15")`
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
* `1.2.18`: `zipalignPath` has changed from a Setting into a Task
