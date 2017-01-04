/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._

object Sample {

  object CliOptions {
    /**
     * Aggregated sample builds are transformed by swapping library dependencies to project ones.
     * This does work play well with dbuild and breaks scala community build. Therefore it was made
     * optional.
     *
     * Default: true
     */
    val aggregateSamples  = sys.props.getOrElse("akka.build.aggregateSamples", "true").toBoolean
  }

  final val akkaOrganization = "com.typesafe.akka"

  def buildTransformer = (ti: BuildLoader.TransformInfo) => ti.base.name match {
    case s if s.startsWith("akka-sample") =>
      ti.unit.copy(
        loadedDefinitions = ti.unit.definitions.copy(
          projects = libraryToProjectDeps(ti.unit.definitions.projects)))
    case _ => ti.unit
  }

  def project(name: String) =
    ProjectRef(file(s"akka-samples/$name"), name)

  private def libraryToProjectDeps(projects: Seq[Project]) =
    projects.map(addProjectDependencies andThen excludeLibraryDependencies andThen enableAutoPlugins)

  private val addProjectDependencies = (project: Project) =>
    project.settings(
      buildDependencies := {
        val projectDependencies = libraryDependencies.value.collect {
          case module if module.organization == akkaOrganization => ProjectRef(file("").toURI, module.name)
        }
        val dependencies = buildDependencies.value
        val classpathWithProjectDependencies = dependencies.classpath.map {
          case (proj, deps) if proj.project == project.id =>
            // add project dependency for every akka library dependency
            (proj, deps ++ projectDependencies.map(ResolvedClasspathDependency(_, None)))
          case (project, deps) => (project, deps)
        }
        BuildDependencies(classpathWithProjectDependencies, dependencies.aggregate)
      }
    )

  private val excludeLibraryDependencies = (project: Project) =>
    project.settings(
      libraryDependencies := libraryDependencies.value.map {
        case module if module.organization == akkaOrganization =>
          /**
           * Exclude self, so it is still possible to know what project dependencies to add.
           * This leaves all transitive dependencies (such as typesafe-config library).
           * However it means that if a sample uses typesafe-config library it must have a
           * library dependency which has a direct transitive dependency to typesafe-config.
           */
          module.excludeAll(ExclusionRule(organization=module.organization))
        case module => module
      }
    )

  /**
   * AutoPlugins are not enabled for externally loaded projects.
   * This adds required settings from the AutoPlugins.
   *
   * Every AutoPlugin that is also meant to be applied to the
   * transformed sample projects should have its settings added here.
   */
  private val enableAutoPlugins = (project: Project) =>
    project.settings((
      Publish.projectSettings ++
      ValidatePullRequest.projectSettings
    ): _*).configs(ValidatePullRequest.ValidatePR)

  private implicit class RichLoadedDefinitions(ld: LoadedDefinitions) {
    def copy(projects: Seq[Project]) =
      new LoadedDefinitions(ld.base, ld.target, ld.loader, ld.builds, projects, ld.buildNames)
  }

  private implicit class RichBuildUnit(bu: BuildUnit) {
    def copy(loadedDefinitions: LoadedDefinitions) =
      new BuildUnit(bu.uri, bu.localBase, loadedDefinitions, bu.plugins)
  }
}
