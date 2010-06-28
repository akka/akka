import sbt._

class Plugins(info: ProjectInfo) extends PluginDefinition(info) {
//  val repo = "GH-pages repo" at "http://mpeltonen.github.com/maven/"
//  val idea = "com.github.mpeltonen" % "sbt-idea-plugin" % "0.1-SNAPSHOT"

  // Repositories: Using module configs to speed up resolution => Repos must be defs!
  def aquteRepo = "aQute Maven Repository" at "http://www.aqute.biz/repo"
  lazy val aquteModuleConfig = ModuleConfiguration("biz.aQute", aquteRepo)
  def databinderRepo = "Databinder Repository" at "http://databinder.net/repo"
  lazy val spdeModuleConfig = ModuleConfiguration("us.technically.spde", databinderRepo)
  
  val bnd4sbt = "com.weiglewilczek.bnd4sbt" % "bnd4sbt"         % "1.0.0.RC3"
  val spdeSbt = "us.technically.spde"       % "spde-sbt-plugin" % "0.4.1"
}
