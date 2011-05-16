/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import sbt._

trait DocParentProject extends ParentProject {
  def apiOutputPath = outputPath / "doc" / "main" / "api"

  def apiProjectDependencies = topologicalSort.dropRight(1)

  def apiMainSources =
    apiProjectDependencies.map {
      case sp: ScalaPaths => sp.mainSources
      case _ => Path.emptyPathFinder
    }.foldLeft(Path.emptyPathFinder)(_ +++ _)

  def apiCompileClasspath =
    apiProjectDependencies.map {
      case bsp: BasicScalaProject => bsp.compileClasspath
      case _ => Path.emptyPathFinder
    }.foldLeft(Path.emptyPathFinder)(_ +++ _)

  def apiLabel = "main"

  def apiMaxErrors = 100

  def apiOptions: Seq[String] = Seq.empty

  lazy val api = apiAction dependsOn (doc) describedAs ("Create combined scaladoc for all subprojects.")

  def apiAction = task {
    val scaladoc = new Scaladoc(apiMaxErrors, buildCompiler)
    scaladoc(apiLabel, apiMainSources.get, apiCompileClasspath.get, apiOutputPath, apiOptions, log)
  }

  lazy val doc = task { None } // dummy task

  val docsPath = info.projectPath / "akka-docs"

  lazy val docs = docsAction describedAs ("Create the reStructuredText documentation.")

  def docsAction = task {
    import Process._
    log.info("Building docs...")
    val exitCode = ((new java.lang.ProcessBuilder("make", "clean", "html", "pdf")) directory docsPath.asFile) ! log
    if (exitCode > 0) Some("Failed to build docs.") else None
  }
}
