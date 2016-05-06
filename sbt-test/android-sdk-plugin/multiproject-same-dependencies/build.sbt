javacOptions in Global ++= List("-source", "1.7", "-target", "1.7")

val sharedSettings = Seq(platformTarget := "android-23", showSdkProgress in Android := false)

val b = project.settings(androidBuildAar:_*).settings(sharedSettings: _*)

val c = project.settings(androidBuildAar:_*).settings(sharedSettings: _*)

val d = project.settings(androidBuildAar:_*).settings(sharedSettings: _*)

val a = project.androidBuildWith(b,c,d).settings(sharedSettings: _*)

libraryDependencies in b += "com.android.support" % "appcompat-v7" % "23.1.1"

libraryDependencies in c += "com.android.support" % "appcompat-v7" % "23.1.1"

libraryDependencies in d += "com.android.support" % "appcompat-v7" % "23.1.1"

minSdkVersion in a := "7"

minSdkVersion in b := "7"

minSdkVersion in c := "7"

minSdkVersion in d := "7"
