instrumentTestRunner in Android :=
  "android.support.test.runner.AndroidJUnitRunner"

libraryDependencies ++=
  "com.android.support.test" % "runner" % "0.2" ::
  "com.android.support.test.espresso" % "espresso-core" % "2.1" ::
  Nil

apkbuildExcludes in Android += "LICENSE.txt"
