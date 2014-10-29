package akka

import sbt._
import sbt.Keys._

object DependencyHelpers {

  case class ScalaVersionDependentModuleID(module: String => Seq[ModuleID]) {
    def %(config: String): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => module(version).map(_ % config))
  }

  object ScalaVersionDependentModuleID {

    implicit def liftConstantModule(mod: ModuleID): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(_ => Seq(mod))

    def fromPF(f: PartialFunction[String, ModuleID]): ScalaVersionDependentModuleID =
      ScalaVersionDependentModuleID(version => if (f.isDefinedAt(version)) Seq(f(version)) else Nil)

    def post210Dependency(moduleId: ModuleID): ScalaVersionDependentModuleID = ScalaVersionDependentModuleID.fromPF {
      case version if !version.startsWith("2.10") => moduleId
    }

    def pre211Dependency(moduleId: ModuleID): ScalaVersionDependentModuleID = ScalaVersionDependentModuleID.fromPF {
      case version if version.startsWith("2.10") => moduleId
    }
  }

  /**
   * Use this as a dependency setting if the dependencies contain both static and Scala-version
   * dependent entries.
   */
  def deps(modules: ScalaVersionDependentModuleID*) =
    libraryDependencies <++= scalaVersion(version => modules.flatMap(m => m.module(version)))

}
