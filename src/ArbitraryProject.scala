package android

import sbt._

// TODO implement a good way to clean
// TODO implement a good way to hook into 'update'
/** Load and apply settings to arbitrary remote repositories.
  * SBT does not apply settings to RootProjects in the correct order.
  * Thus, ArbitraryProject exists to allow this configuration.
  * See https://gist.github.com/pfn/6238004 for example usage.
  */
object ArbitraryProject {
  /*
  import sbt.RichURI.fromURI
  // this part is totally ghetto, plucking a State out of thin air
  private object Config extends xsbti.AppConfiguration {
    override def baseDirectory = ???
    override def arguments = ???
    override def provider = new xsbti.AppProvider {
      override def entryPoint(): Class[_] = ???
      override def components(): xsbti.ComponentProvider = ???
      override def id() = new xsbti.ApplicationID {
        override def classpathExtra(): Array[java.io.File] = ???
        override def crossVersioned(): Boolean = ???
        override def crossVersionedValue(): xsbti.CrossValue = ???
        override def groupID(): String = ???
        override def mainClass(): String = ???
        override def mainComponents(): Array[String] = ???
        override def name(): String = ???
        override def version(): String = "0.13"
      }
      override def loader(): ClassLoader = ???
      override def mainClass(): Class[_ <: xsbti.AppMain] = ???
      override def mainClasspath(): Array[java.io.File] = ???
      override def newMain(): xsbti.AppMain = ???
      override def scalaProvider(): xsbti.ScalaProvider = ???
    }
  }
  private val st = State(Config, Nil, Set.empty, None, Nil, State.newHistory,
    BuiltinCommands.initialAttributes,
    StandardMain.initialGlobalLogging, State.Continue )
  // end ghetto part
  private val globalBase = BuildPaths.getGlobalBase(st)
  private val staging = BuildPaths.getStagingDirectory(st, globalBase)

  /** return the cloned location */
  def git(uri: URI): File = {
    // null for LoadBuildConfiguration, not necessary in this use-case
    // otherwise it's a world-of-pain to configure
    val info = new BuildLoader.ResolveInfo(uri, staging, null, st)

    Resolvers.git(info).get()
    Resolvers.uniqueSubdirectoryFor(uri.copy(scheme = "git"), staging)
  }

  /** use by overriding buildLoaders on Build.
    * Pass a Map from the RootProject's location to the settings to apply
    */
  def settingsLoader(settings: Map[File, Seq[Setting[_]]]) = {
    val settingsMap = settings map { case (k,v) => (k.toURI,v) }
    BuildLoader.transform(in => {
      if (settingsMap.contains(in.uri)) {
        val oldDefns = in.unit.definitions
        val oldBuild = in.unit.definitions.builds.head
        val newBuild = new Build {
          override val buildLoaders = oldBuild.buildLoaders
          override val settings = oldBuild.settings
          override val projects = oldBuild.projects
          override def projectDefinitions(b: File) = {
            val newProject = (oldBuild projectDefinitions b).head match {
              case p: ProjectDefinition[_] => p.copy(
                settings = (p: ProjectDefinition[_]).settings ++ settingsMap(in.uri))
            }
            Seq(newProject)
          }
        }

        val newDefns = new LoadedDefinitions(
          oldDefns.base, oldDefns.target, oldDefns.loader,
          Seq(newBuild), oldDefns.projects map { p =>
            p.copy(settings = (p: ProjectDefinition[_]).settings ++ settingsMap(in.uri))
          }, oldDefns.buildNames)
        new BuildUnit(
          in.unit.uri, in.unit.localBase, newDefns, in.unit.plugins)
      } else in.unit
    }) :: Nil
  }
  */
}

// vim: set ts=4 sw=4 et:
