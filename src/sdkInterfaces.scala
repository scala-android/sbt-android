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
    log.warn(s)
    if (e != null)
      log.trace(e)
  }

  override def logInfo(s: String) = log.debug(s)
}

case class PrintingProgressIndicator(showProgress: Boolean = true) extends ProgressIndicatorAdapter {
  val SPINNER =
    "|"  ::
    "/"  ::
    "-"  ::
    "\\" ::
    Nil
  var counter = 0
  var text = Option.empty[String]
  var secondary = Option.empty[String]
  var indeterminate = false
  var progress = Option.empty[String]
  var fraction = 0.0

  override def getFraction = fraction
  override def setFraction(v: Double) = {
    progress = Some(f"${v*100}%3.0f%%")
    indeterminate = false
    if ((v*100).toInt != (fraction*100).toInt) {
      printProgress()
      if (v == 1.0 && showProgress)
        println()
    }
    fraction = v
  }
  override def setIndeterminate(b: Boolean) = {
    indeterminate = b
    printProgress()
  }
  override def setText(s: String) = {
    val newtext =  Option(s).filter(_.trim.nonEmpty)
    if (newtext != text) {
      text = newtext
      printProgress()
    }
  }
  override def setSecondaryText(s: String) = {
    val newtext =  Option(s).filter(_.trim.nonEmpty)
    if (secondary != newtext) {
      secondary = newtext
      printProgress()
    }
  }
  def printProgress(): Unit = {
    val prog = if (indeterminate) {
      counter = counter + 1
      val a = SPINNER(counter % 4)
      val b = SPINNER((counter + 2) % 4)
      s"$a$b%"
    } else {
      progress.getOrElse(f"${0}%3d%%")
    }
    val indicator = secondary.fold(text.getOrElse(""))(s => text.getOrElse("") + " / " + s)
    if (showProgress)
      print(f"${indicator.take(72)}%-72s $prog%6s\r")
  }

  override def logWarning(s: String, e: Throwable) = {
    println("[warn] " + s)
  }
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
case class SbtILogger() extends ILogger {
  def apply(log: Logger) = this.log = Some(log)
  private[this] var log: Option[Logger] = None
  override def verbose(fmt: java.lang.String, args: Object*) {
    log.foreach(_.debug(String.format(fmt, args:_*)))
  }
  override def info(fmt: java.lang.String, args: Object*) {
    log.foreach(_.debug(String.format(fmt, args:_*)))
  }
  override def warning(fmt: java.lang.String, args: Object*) {
    log.foreach(_.warn(String.format(fmt, args:_*)))
  }
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) {
    log.foreach { l => l.error(String.format(fmt, args: _*))
      if (t != null)
        l.trace(t)
    }
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

case class SbtAndroidErrorReporter() extends ErrorReporter(ErrorReporter.EvaluationMode.STANDARD) {
  private[this] var log = Option.empty[Logger]
  def apply(l: Logger) = log = Some(l)

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
        log.foreach(_.error(messageString))
      case Kind.WARNING =>
        log.foreach(_.warn(messageString))
      case Kind.INFO =>
        log.foreach(_.info(messageString))
      case Kind.STATISTICS =>
        log.foreach(_.debug(messageString))
      case Kind.UNKNOWN =>
        log.foreach(_.debug(messageString))
      case Kind.SIMPLE =>
        log.foreach(_.info(messageString))
    }
  }

  override def handleSyncIssue(data: String, `type`: Int, severity: Int, msg: String) = {
    if (severity == SyncIssue.SEVERITY_WARNING) {
      log.foreach(_.warn(s"android sync: data=$data, type=${`type`}, msg=$msg"))
    } else if (severity == SyncIssue.SEVERITY_ERROR) {
      log.foreach(_.error(s"android sync: data=$data, type=${`type`}, msg=$msg"))
    }
    new SyncIssue {
      override def getType = `type`
      override def getData = data
      override def getMessage = msg
      override def getSeverity = severity
    }
  }
}
