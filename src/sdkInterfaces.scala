package android

import com.android.builder.core.ErrorReporter
import com.android.builder.model.SyncIssue
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.Message.Kind
import com.android.ide.common.process.BaseProcessOutputHandler.BaseProcessOutput
import com.android.ide.common.process._
import com.android.repository.api.ProgressIndicatorAdapter
import com.android.utils.ILogger
import sbt.{Fork, ForkOptions, Logger}

import collection.JavaConverters._

/**
  * @author pfnguyen
  */

case class SbtAndroidProgressIndicator(log: Logger) extends ProgressIndicatorAdapter {
  override def logError(s: String, e: Throwable) = {
    log.error(s)
    if (e != null)
      log.trace(e)
  }

  override def logWarning(s: String, e: Throwable) = {
    log.debug(s)
    if (e != null)
      log.trace(e)
  }

  override def logInfo(s: String) = log.debug(s)
}
object NullProgressIndicator extends ProgressIndicatorAdapter

object NullLogger extends ILogger {
  override def verbose(fmt: java.lang.String, args: Object*) = ()
  override def info(fmt: java.lang.String, args: Object*) = ()
  override def warning(fmt: java.lang.String, args: Object*) = ()
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) = ()
}

case class SbtDebugLogger(lg: Logger) extends ILogger {
  override def verbose(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def info(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def warning(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
    if (t != null)
      lg.trace(t)
  }
}
case class SbtLogger(lg: Logger) extends ILogger {
  override def verbose(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def info(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def warning(fmt: java.lang.String, args: Object*) {
    lg.warn(String.format(fmt, args:_*))
  }
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) {
    lg.error(String.format(fmt, args:_*))
    if (t != null)
      lg.trace(t)
  }
}

case class SbtProcessOutputHandler(lg: Logger) extends BaseProcessOutputHandler {
  override def handleOutput(processOutput: ProcessOutput) = {
    processOutput match {
      case p: BaseProcessOutput =>
        val stdout = p.getStandardOutputAsString
        if (!stdout.isEmpty)
          lg.debug(stdout)
        val stderr = p.getErrorOutputAsString
        if (!stderr.isEmpty)
          lg.warn(stderr)
    }
  }
}

object SbtJavaProcessExecutor extends JavaProcessExecutor {
  import language.existentials
  override def execute(javaProcessInfo: JavaProcessInfo, processOutputHandler: ProcessOutputHandler) = {
    val options = ForkOptions(
      envVars = javaProcessInfo.getEnvironment.asScala.map { case ((x, y)) => x -> y.toString }.toMap,
      runJVMOptions = javaProcessInfo.getJvmArgs.asScala ++
        ("-cp" :: javaProcessInfo.getClasspath :: Nil))
    val r = Fork.java(options, (javaProcessInfo.getMainClass :: Nil) ++ javaProcessInfo.getArgs.asScala)

    new ProcessResult {
      override def assertNormalExitValue() = {
        if (r != 0) {
          val e = new ProcessException(
            s"Android SDK command failed ($r); see prior messages for details")
          e.setStackTrace(Array.empty)
          throw e
        }
        this
      }

      override def rethrowFailure() = this
      override def getExitValue = r
    }
  }
}

case class SbtAndroidErrorReporter(log: Logger) extends ErrorReporter(ErrorReporter.EvaluationMode.STANDARD) {

  override def receiveMessage(message: Message) = {

    val errorStringBuilder = new StringBuilder
    message.getSourceFilePositions.asScala.foreach { pos =>
      errorStringBuilder.append(pos.toString)
      errorStringBuilder.append(' ')
    }
    if (errorStringBuilder.nonEmpty)
      errorStringBuilder.append(": ")
    if (message.getToolName.isPresent) {
      errorStringBuilder.append(message.getToolName.get).append(": ")
    }
    errorStringBuilder.append(message.getText).append("\n")


    val messageString = errorStringBuilder.toString

    message.getKind match {
      case Kind.ERROR =>
        log.error(messageString)
      case Kind.WARNING =>
        log.warn(messageString)
      case Kind.INFO =>
        log.info(messageString)
      case Kind.STATISTICS =>
        log.debug(messageString)
      case Kind.UNKNOWN =>
        log.debug(messageString)
      case Kind.SIMPLE =>
        log.info(messageString)
    }
  }

  override def handleSyncIssue(data: String, `type`: Int, severity: Int, msg: String) = {
    if (severity == SyncIssue.SEVERITY_WARNING) {
      log.warn(s"android sync: data=$data, type=${`type`}, msg=$msg")
    } else if (severity == SyncIssue.SEVERITY_ERROR) {
      log.error(s"android sync: data=$data, type=${`type`}, msg=$msg")
    }
    new SyncIssue {
      override def getType = `type`
      override def getData = data
      override def getMessage = msg
      override def getSeverity = severity
    }
  }
}
