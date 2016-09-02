import collection.JavaConversions._

enablePlugins(AndroidPlugin)
 
libraryDependencies ++= Seq(
  "org.apache.maven" % "maven-ant-tasks" % "2.1.3" % "test",
  "org.robolectric" % "robolectric" % "2.4" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.11" % "test"
)
 
exportJars in Test := false // necessary until android-sdk-plugin 1.3.12
 
// or else @Config throws an exception, yay
unmanagedClasspath in Test ++= (bootClasspath in Android).value

showSdkProgress in Android := false
