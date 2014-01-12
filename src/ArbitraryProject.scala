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
  import sbt.RichURI.fromURI
  // this part is totally ghetto, plucking a State out of thin air
  private object Config extends xsbti.AppConfiguration {
    override def baseDirectory = file("")
    override def arguments = Array.empty
    override def provider = null
  }
  private val st = StandardMain.initialState(Config, Nil, Nil)
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
              case p: ProjectDefinition[_] =>
                Project(p.id, p.base, p.aggregate, p.dependencies, p.delegates,
                  Defaults.defaultSettings ++ settingsMap(in.uri),
                  p.configurations)
            }
            Seq(newProject)
          }
        }
        val newDefns = new Load.LoadedDefinitions(
          oldDefns.base, oldDefns.target, oldDefns.loader, Seq(newBuild),
          oldDefns.buildNames)
        new Load.BuildUnit(
          in.unit.uri, in.unit.localBase, newDefns, in.unit.plugins)
      } else in.unit
    }) :: Nil
  }
}

// vim: set ts=4 sw=4 et:
