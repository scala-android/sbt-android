package android

import com.android.builder.model.SigningConfig
import com.android.builder.signing.DefaultSigningConfig
import com.android.ide.common.signing.KeystoreHelper
import sbt._
import language.postfixOps

trait ApkSigningConfig {
  def storeType = "jks"
  def keyPass = Option.empty[String]
  def keystore: File
  def alias: String
  def storePass: String

  private[android] def toSigningConfig(name: String) = new SigningConfig {
    override def getName = name
    override def getKeyAlias = alias
    override def isSigningReady = true
    override def getStoreType = storeType
    override def getStorePassword = storePass
    override def getKeyPassword = keyPass getOrElse storePass
    override def getStoreFile = keystore
  }
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

case class DebugSigningConfig(override val keystore: File = file(KeystoreHelper.defaultDebugKeystoreLocation),
                              override val storePass: String = DefaultSigningConfig.DEFAULT_PASSWORD,
                              override val alias: String = DefaultSigningConfig.DEFAULT_ALIAS,
                              override val keyPass: Option[String] = None,
                              override val storeType: String = "jks") extends ApkSigningConfig {
  if(!keystore.exists) {
    KeystoreHelper.createDebugStore(storeType, keystore, storePass,
      keyPass getOrElse storePass, alias, NullLogger)
  }
}
