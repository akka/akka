import sbt._

class Plugins(info: ProjectInfo) extends PluginDefinition(info) {

  // -------------------------------------------------------------------------------------------------------------------
        // All repositories *must* go here! See ModuleConigurations below.
  // -------------------------------------------------------------------------------------------------------------------
        object Repositories {
          lazy val AquteRepo      = "aQute Maven Repository" at "http://www.aqute.biz/repo"
          lazy val DatabinderRepo = "Databinder Repository" at "http://databinder.net/repo"
          lazy val EmbeddedRepo   = "Embedded Repo" at (info.projectPath / "embedded-repo").asURL.toString
        }

  // -------------------------------------------------------------------------------------------------------------------
  // ModuleConfigurations
  // Every dependency that cannot be resolved from the built-in repositories (Maven Central and Scala Tools Releases)
  // must be resolved from a ModuleConfiguration. This will result in a significant acceleration of the update action.
  // Therefore, if repositories are defined, this must happen as def, not as val.
  // -------------------------------------------------------------------------------------------------------------------
        import Repositories._
        lazy val aquteModuleConfig      = ModuleConfiguration("biz.aQute", AquteRepo)
  lazy val codeFellowModuleConfig = ModuleConfiguration("de.tuxed", EmbeddedRepo)
  lazy val spdeModuleConfig       = ModuleConfiguration("us.technically.spde", DatabinderRepo)

  // -------------------------------------------------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------------------------------------------------
  lazy val bnd4sbt    = "com.weiglewilczek.bnd4sbt" % "bnd4sbt"           % "1.0.0.RC4"
  lazy val codeFellow = "de.tuxed"                  % "codefellow-plugin" % "0.3" // for code completion and more in VIM
  lazy val spdeSbt    = "us.technically.spde"       % "spde-sbt-plugin"   % "0.4.1"
}
