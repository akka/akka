import sbt._

class Plugins(info: ProjectInfo) extends PluginDefinition(info) {
  val databinderRepo = "Databinder Repository" at "http://databinder.net/repo"
  val spdeSbt = "us.technically.spde" % "spde-sbt-plugin" % "0.4.1"
//  val repo = "GH-pages repo" at "http://mpeltonen.github.com/maven/"
//  val idea = "com.github.mpeltonen" % "sbt-idea-plugin" % "0.1-SNAPSHOT"

  lazy val bnd4sbt = "com.weiglewilczek" % "bnd4sbt" % "0.4"
}
