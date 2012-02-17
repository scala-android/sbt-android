package com.hanhuy.sbt.android

import java.util.Properties
import java.io.{File,FilenameFilter}
import sbt._
import sbt.Keys._

object AndroidSdkPlugin extends Plugin {
    implicit def toFilenameFilter(f: String => Boolean) = new FilenameFilter {
        override def accept(file: File, name: String) = f(name)
    }

    override lazy val settings: Seq[Setting[_]] = inConfig(Compile) (Seq(
        //useProguard := false,
        //autoScalaLibrary in GlobalScope := false,
        //manifestPath <<= (baseDirectory, manifestName) map((s,m) => Seq(s / m)) map (x=>x),
        //proguardOptimizations := Seq.empty,
        //mainResPath <<= (baseDirectory, resDirectoryName) (_ / _) map (x=>x),
        //mainAssetsPath <<= (baseDirectory, assetsDirectoryName) (_ / _),
        scalaSource <<= baseDirectory (_ / "src"),
        javaSource <<= baseDirectory (_ / "src")
    ))
}
