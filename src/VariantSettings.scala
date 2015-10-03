package android

import sbt._
import VariantSettings._

/**
 * @author pfnguyen
 */
object VariantSettings {
  type VariantMap = Map[ProjectRef, Seq[Setting[_]]]
  def empty = VariantSettings(Map.empty, Nil)

  private[android] val explicitGlobalLogLevels = AttributeKey[Boolean](
    "explicit-global-log-levels", "True if the global logging levels were explicitly set by the user.", 10)
  val variantSettings = AttributeKey[VariantSettings](
    "flavor-settings", "Tracks current build, project, and setting flavor modifications.", KeyRanks.DSetting)

  def variant(state: State): VariantSettings = state.get(variantSettings) getOrElse empty
  def withVariant(s: State)(f: VariantSettings => State): State = f(variant(s))

  def clearVariant(s: State): State = {
    val session = Project.extract(s).session
    s.log.info("Clearing variant configuration from " + session.current.project)
    withVariant(s)(variants => reapply(session,
      variants.copy(append = variants.append - session.current), Project.structure(s), s))
  }
  def clearAllVariants(s: State): State = {
    val session = Project.extract(s).session
    s.log.info("Clearing variant configuration from all projects")
    withVariant(s)(variants => reapply(session,
      variants.clearExtraSettings, Project.structure(s), s))
  }
  def clearVariant(s: State, ref: ProjectRef): State = {
    val session = Project.extract(s).session
    s.log.info("Clearing variant configuration from " + ref.project)
    withVariant(s)(variants => reapply(session,
      variants.copy(append = variants.append - ref), Project.structure(s), s))
  }

  def setVariant(s: State,
                 project: ProjectRef,
                 buildType: Option[String],
                 flavor: Option[String]): State = withVariant(s) { variants =>
    if (buildType.nonEmpty || flavor.nonEmpty) {
      val extracted = Project.extract(s)
      val buildTypes = extracted.getOpt(Keys.buildTypes in Keys.Android in project)
      val flavors = extracted.getOpt(Keys.flavors in Keys.Android in project)
      val ss: Seq[Setting[_]] =
        flavor.map(f => flavors.toSeq.flatMap(_.getOrElse(f, Nil))).getOrElse(Nil) ++
          buildType.map(t => buildTypes.toSeq.flatMap(_.getOrElse(t, Nil))).getOrElse(Nil)

        val scopeMod = new Def.MapScoped {
          override def apply[T](a: Def.ScopedKey[T]) = {
            val scope0 = if (a.scope.project == This)
              a.scope.copy(project = Select(project)) else a.scope
            val scope1 = if (scope0.task == This) scope0.copy(task = Global) else scope0
            val scope2 = if (scope1.extra == This) scope1.copy(extra = Global) else scope1
            a.copy(scope = scope2)
          }
        }
      val ss2 = ss map {
        _.mapKey(scopeMod).mapReferenced(scopeMod)
      }
      val newVariant = variants.copy(append = variants.append + ((project, ss2)))
      val bt = buildType.fold("")(t => s"buildType=$t ")
      val fl = flavor.fold("")(f => s"flavor=$f ")
      s.log.info(s"Applying variant settings $bt${fl}to ${project.project}...")
      reapply(extracted.session, newVariant, extracted.structure, s)
    } else s
  }

  def reapply(session: SessionSettings, newVariant: VariantSettings, structure: BuildStructure, s: State): State =
  {
    // Here, for correct behavior, we also need to re-inject a settings logger, as we'll be re-evaluating settings.
    val loggerInject = sbt.Keys.sLog in GlobalScope := new Logger {
      private[this] val ref = new java.lang.ref.WeakReference(s.globalLogging.full)
      private[this] def slog: Logger = Option(ref.get) getOrElse sys.error("Settings logger used after project was loaded.")

      override val ansiCodesSupported = slog.ansiCodesSupported
      override def trace(t: => Throwable) = slog.trace(t)
      override def success(message: => String) = slog.success(message)
      override def log(level: Level.Value, message: => String) = slog.log(level, message)
    }
    val withLogger = newVariant.appendRaw(loggerInject :: Nil)
    val newStructure = Load.reapply(session.mergeSettings ++ withLogger.mergeSettings.toList, structure)(showContextKey(session, structure))
    setProject(newVariant, newStructure, s)
  }

  def showContextKey(session: SessionSettings, structure: BuildStructure, keyNameColor: Option[String] = None): Show[ScopedKey[_]] =
    Def.showRelativeKey(session.current, structure.allProjects.size > 1, keyNameColor)
  def setProject(variants: VariantSettings, structure: BuildStructure, s: State): State = {
    val unloaded = Project.runUnloadHooks(s)
    val (onLoad, onUnload) = Project.getHooks(structure.data)
    val newAttrs = unloaded.attributes
      .put(sbt.Keys.stateBuildStructure, structure)
      .put(variantSettings, variants)
      .put(sbt.Keys.onUnload.key, onUnload)
    val newState = unloaded.copy(attributes = newAttrs)
    onLoad(setGlobalLogLevels(Project.updateCurrent(newState), structure.data))
  }

  private[this] def hasExplicitGlobalLogLevels(s: State): Boolean = s.get(explicitGlobalLogLevels) getOrElse false
  private[this] def setGlobalLogLevels(s: State, data: Settings[Scope]): State =
    if (hasExplicitGlobalLogLevels(s))
      s
    else {
      val logging = s.globalLogging
      def get[T](key: SettingKey[T]) = key in GlobalScope get data
      def transfer(l: AbstractLogger, traceKey: SettingKey[Int], levelKey: SettingKey[Level.Value]) {
        get(traceKey).foreach(l.setTrace)
        get(levelKey).foreach(l.setLevel)
      }
      logging.full match {
        case a: AbstractLogger => transfer(a, sbt.Keys.traceLevel, sbt.Keys.logLevel)
        case _                 => ()
      }
      transfer(logging.backed, sbt.Keys.persistTraceLevel, sbt.Keys.persistLogLevel)
      s
    }

}
final case class VariantSettings(append: VariantMap, rawAppend: Seq[Setting[_]]) {
  def appendRaw(ss: Seq[Setting[_]]): VariantSettings = copy(rawAppend = rawAppend ++ ss)
  def mergeSettings: Seq[Setting[_]] = merge(append) ++ rawAppend
  def clearExtraSettings: VariantSettings = copy(append = Map.empty, rawAppend = Nil)

  private[this] def merge(map: VariantMap): Seq[Setting[_]] = map.values.toList.flatten[Setting[_]]
}
