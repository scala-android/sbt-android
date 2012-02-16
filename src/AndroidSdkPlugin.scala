package com.hanhuy.sbt.android

import java.util.Properties
import java.io.{File,FilenameFilter}

import sbt._
object AndroidSdkPlugin extends Plugin {
    implicit def toFilenameFilter(f: String => Boolean) = new FilenameFilter {
        override def accept(file: File, name: String) = f(name)
    }
}
