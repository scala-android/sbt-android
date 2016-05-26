package sbt
/**
  * @author pfnguyen
  */
object LoadForwarder {
  def reapply(newSettings: Seq[Setting[_]], structure: internal.BuildStructure)(implicit display: Show[ScopedKey[_]]) =
    internal.Load.reapply(newSettings, structure)(display)
}
