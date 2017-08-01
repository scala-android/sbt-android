organization	:= "test"

name			:= "test"

version			:= "0.1.0-SNAPSHOT"

organization	in ThisBuild	:= organization.value

version			in ThisBuild	:= version.value

scalaVersion	in ThisBuild	:= "2.11.4"

lazy val `test`			= project in file(".") aggregate `test-client`

lazy val `test-client`	= project in file("sub/client")
		
TaskKey[File]("bundle")	:= (android.Keys.packageDebug	in (`test-client`, android.Keys.Android)).value

showSdkProgress in Android := false

buildToolsVersion := Some("26.0.1")
