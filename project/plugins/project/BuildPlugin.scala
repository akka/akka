import sbt._
import Keys._

object PluginDef extends Build {

  lazy val formatterPlugin = ProjectRef( uri("git://github.com/viktorklang/sbt-cool-plugins.git"), "Formatter")
  
  lazy override val projects = Seq(root)
  
  lazy val root = Project("plugins", file(".")) dependsOn (formatterPlugin)
}