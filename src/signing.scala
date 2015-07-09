package android

import sbt._
import language.postfixOps

trait ApkSigningConfig {
  def storeType = "jks"
  def keyPass = Option.empty[String]
  def keystore: File
  def alias: String
  def storePass: String
}
case class PlainSigningConfig(override val keystore: File,
                              override val storePass: String,
                              override val alias: String,
                              override val keyPass: Option[String] = None,
                              override val storeType: String = "jks") extends ApkSigningConfig

case class PromptStorepassSigningConfig(override val keystore: File,
                                        override val alias: String,
                                        override val storeType: String = "jks") extends ApkSigningConfig {
  override lazy val storePass =
    System.console.readPassword("Enter keystore password: ") mkString
}

case class PromptPasswordsSigningConfig(override val keystore: File,
                                        override val alias: String,
                                        override val storeType: String = "jks") extends ApkSigningConfig {
  import System.console
  override lazy val storePass =
    console.readPassword("Enter keystore password: ") mkString
  override lazy val keyPass =
    Option(console.readPassword("Enter password for key '%s': " format alias) mkString)
}
