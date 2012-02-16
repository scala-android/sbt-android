sbtPlugin := true

name := "android-sdk-plugin"

organization := "com.hanhuy"

scalaSource in Compile <<= baseDirectory(_ / "src")

scalaSource in Test <<= baseDirectory(_ / "test")

packageBin in Compile := file("bin")
