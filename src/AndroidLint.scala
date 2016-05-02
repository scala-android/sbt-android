package android

import java.text.MessageFormat
import java.util

import com.android.sdklib.SdkVersionInfo
import com.android.utils.SdkUtils
import sbt.Keys.TaskStreams
import sbt._

import com.android.tools.lint._
import com.android.tools.lint.checks.ApiDetector
import com.android.tools.lint.client.api.{IssueRegistry, LintClient}
import com.android.tools.lint.detector.api.{Project => LintProject, Location, TextFormat, Severity, Issue}

import collection.JavaConverters._
import language.postfixOps

/**
 * @author pfnguyen
 */
object AndroidLint {

  def apply(layout: ProjectLayout, flags: LintCliFlags, detectors: Seq[Issue], strict: Boolean,
            minSdk: String, targetSdk: String, s: TaskStreams)(implicit m: BuildOutput.Converter): Unit = {
    val client = AndroidLint.SbtLintClient(layout, flags, minSdk, targetSdk)
    flags.getReporters.clear()
    flags.getReporters.add(SbtLintReporter(client, strict, s))
    client.run(AndroidLint.LintDetectorIssues(detectors), List(layout.base).asJava)
  }
  val lintDetectorList = List(
    ApiDetector.OVERRIDE,
    ApiDetector.UNUSED,
    ApiDetector.UNSUPPORTED
  )

  case class LintDetectorIssues(issues: Seq[Issue]) extends IssueRegistry {
    override def getIssues = issues.asJava
  }

  case class SbtLintClient(layout: ProjectLayout, flags: LintCliFlags, minSdk: String, targetSdk: String)(implicit m: BuildOutput.Converter) extends LintCliClient(flags, "sbt-android") {
    override def addProgressPrinter() = {
//      super.addProgressPrinter()
    }

    override def isProjectDirectory(dir: File) = {
      dir.getCanonicalPath == layout.base.getCanonicalPath
    }

    override def createLintRequest(files: java.util.List[File]) = {
      val r = super.createLintRequest(files)
      r.setProjects(files.asScala map { f =>
        SbtProject(this, layout, minSdk, targetSdk): LintProject
      } asJava)
      r
    }

  }
  case class SbtProject(client: LintClient, layout: ProjectLayout, minSdk: String, targetSdk: String)(implicit m: BuildOutput.Converter)
    extends LintProject(client, layout.base, layout.base) {
    override def getJavaClassFolders = List(layout.classes).asJava
    override def getManifestFiles    = List(layout.manifest).asJava
    override def getResourceFolders  = List(layout.res).asJava
    override def getMinSdkVersion    = SdkVersionInfo.getVersion(minSdk, client.getTargets)
    override def getTargetSdkVersion = SdkVersionInfo.getVersion(targetSdk, client.getTargets)
  }

  case class SbtLintReporter(client: LintCliClient,
                             strict: Boolean,
                             s: TaskStreams) extends Reporter(client, null) {
    lazy val fmt = new MessageFormat("{0} {1}{0,choice,0#s|1#|1<s}")
    def fmtE(n: Int) = fmt.format(Array(n, "error"),   new StringBuffer, null)
    def fmtW(n: Int) = fmt.format(Array(n, "warning"), new StringBuffer, null)
    override def write(errorCount: Int, warningCount: Int, issues: util.List[Warning]) = {
      val short = !client.getFlags.isShowEverything
      val is = issues.asScala.toList
      if (is.isEmpty) {
        s.log.debug("lint found no issues")
      } else {
        val all = is zip (None :: (is map (i => Option(i.issue))))
        all foreach { case (issue, previous) =>
          val b = new StringBuilder
          val log = issue.severity match {
            case Severity.FATAL         => s.log.error(_: String)
            case Severity.ERROR         => s.log.error(_: String)
            case Severity.WARNING       => s.log.warn(_: String)
            case Severity.INFORMATIONAL => s.log.info(_: String)
            case Severity.IGNORE        => s.log.debug(_: String)
          }
          if (!previous.exists(_ == issue.issue)) {
            explainIssue(issue.issue, log)
          }
          if (issue.issue != null) {
            b.append('[').append(issue.issue.getId).append("] ")
          }
          if (issue.path != null) {
            b.append(issue.path)
            b.append(':')
            if (issue.line >= 0) {
              b.append(String.valueOf(issue.line + 1))
              b.append(':')
            }
          }
          b.append(TextFormat.RAW.convertTo(issue.message, TextFormat.TEXT))
          Option(issue.errorLine) filter (_.nonEmpty) foreach b.append("\n").append
          log(b.mkString)

          for {
            l1 <- Option(issue.location)
            location <- Option(issue.location.getSecondary)
          } {
            if (!short) {
              val s = alsoAffects(issue, Some(l1)).mkString
              if (s.length > 0) {
                log("Also affects: " + SdkUtils.wrap(s, 72, "      "))
              }
            }
          }

          if (issue.isVariantSpecific) {
            val (msg, names) = if (issue.includesMoreThanExcludes) {
              ("Applies to variants: ", issue.getIncludedVariantNames)
            } else {
              ("Does not apply to variants: ", issue.getExcludedVariantNames)
            }
            log(msg + names.asScala.mkString(", "))
          }
        }

        val errstr = s"lint found ${fmtE(errorCount)}, ${fmtW(warningCount)}"
        if (strict && errorCount > 0)
          PluginFail(errstr)
        else {
          if (errorCount > 0)
            s.log.error(errstr)
          else
            s.log.warn(s"lint found ${fmtW(warningCount)}")
        }
      }
    }

    def getDisplayPath(prj: LintProject, file: File) = {
      var path = file.getPath
      if (!client.getFlags.isFullPath && path.startsWith(prj.getReferenceDir.getPath)) {
        var chop = prj.getReferenceDir.getPath.length
        if (path.length() > chop && path.charAt(chop) == java.io.File.separatorChar) {
          chop += 1
        }
        path = path.substring(chop)
        if (path.isEmpty) {
          path = file.getName
        }
      } else if (client.getFlags.isFullPath) {
        path = file.getCanonicalPath
      }

      path
    }

    private def alsoAffects(issue: Warning, loc: Option[Location], b: StringBuilder = new StringBuilder): StringBuilder = {

      (for {
        location <- loc
        msg <- Option(location.getMessage) if msg.nonEmpty
      } yield {
        if (b.nonEmpty) b.append(", ")
        b.append(getDisplayPath(issue.project, location.getFile))
        Option(location.getStart) foreach { pos =>
          val line = pos.getLine
          if (line >= 0) {
            b.append(':')
            b.append(String.valueOf(line + 1))
          }
        }
        alsoAffects(issue, Option(location.getSecondary), b)
      }) getOrElse b
    }
    def explainIssue(issue: Issue, log: String => Unit): Unit = {
      val explanation = issue.getExplanation(TextFormat.TEXT)
      val indent = "    "
      if (explanation.nonEmpty && client.getFlags.isExplainIssues) {
        val formatted = SdkUtils.wrap(explanation, 72 - indent.length, null)
        log(s"Explanation for issues of type '${issue.getId}:'")
        formatted.split("\n") foreach { line =>
          if (line.nonEmpty)
            log(indent + line)
        }
        issue.getMoreInfo.asScala foreach { more =>
          if (!formatted.contains(more))
            log(indent + more)
        }
      }
    }
  }
}
