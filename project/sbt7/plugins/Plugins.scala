import sbt._

class Plugins(info: ProjectInfo) extends PluginDefinition(info) {

  // -------------------------------------------------------------------------------------------------------------------
  // All repositories *must* go here! See ModuleConigurations below.
  // -------------------------------------------------------------------------------------------------------------------
  object Repositories {
    lazy val DatabinderRepo = "Databinder Repository" at "http://databinder.net/repo"
  }

  // -------------------------------------------------------------------------------------------------------------------
  // ModuleConfigurations
  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // must be resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.
  // Therefore, if repositories are defined, this must happen as def, not as val.
  // -------------------------------------------------------------------------------------------------------------------
  import Repositories._
  lazy val spdeModuleConfig = ModuleConfiguration("us.technically.spde", DatabinderRepo)

  // -------------------------------------------------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------------------------------------------------
  lazy val bnd4sbt = "com.weiglewilczek.bnd4sbt" % "bnd4sbt"         % "1.0.2"
  lazy val spdeSbt = "us.technically.spde"       % "spde-sbt-plugin" % "0.4.2"
  lazy val formatter = "com.github.olim7t" % "sbt-scalariform" % "1.0.3"
}
