package android

import sbt.MessageOnlyException

private[android] trait PluginFail {
  def fail[A](msg: => String): A = {
    throw new MessageOnlyException(msg)
  }
}

private[android] object PluginFail extends PluginFail {
  def apply[A](msg: => String): A = fail(msg)
}