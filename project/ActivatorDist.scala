package akka

import sbt._
import sbt.Keys._
import sbt.classpath.ClasspathUtilities
import sbt.Project.Initialize
import java.io.File

object ActivatorDist {

  val activatorDistDirectory = SettingKey[File]("activator-dist-directory")
  val activatorDist = TaskKey[File]("activator-dist", "Create a zipped distribution of each activator sample.")

  lazy val settings: Seq[Setting[_]] = Seq(
    activatorDistDirectory <<= crossTarget / "activator-dist",
    activatorDist <<= activatorDistTask
  )

  def aggregatedProjects(projectRef: ProjectRef, structure: Load.BuildStructure, exclude: Set[String]): Seq[ProjectRef] = {
    val aggregate = Project.getProject(projectRef, structure).toSeq.flatMap(_.aggregate)
    aggregate flatMap { ref =>
      if (exclude contains ref.project) Seq.empty
      else ref +: aggregatedProjects(ref, structure, exclude)
    }
  }

  def activatorDistTask: Initialize[Task[File]] = {
    (thisProjectRef, baseDirectory, activatorDistDirectory, version, buildStructure, streams) map {
      (project, projectBase, activatorDistDirectory, version, structure, s) => {
        val exclude = Set("akka-sample-osgi-dining-hakkers", "akka-sample-osgi-dining-hakkers-api", 
            "akka-sample-osgi-dining-hakkers-command", "akka-sample-osgi-dining-hakkers-core", 
            "akka-sample-osgi-dining-hakkers-uncommons", "akka-sample-osgi-dining-hakkers-integration")
        val allProjects = aggregatedProjects(project, structure, exclude).flatMap(p => Project.getProject(p, structure))
        val rootGitignoreLines = IO.readLines(AkkaBuild.akka.base / ".gitignore")
        for (p <- allProjects) {
         val localGitignoreLines = if ((p.base / ".gitignore").exists) IO.readLines(p.base / ".gitignore") else Nil
         val gitignorePathFinder = (".gitignore" :: localGitignoreLines ::: rootGitignoreLines).foldLeft(PathFinder.empty)(
             (acc, x) => acc +++ (p.base * x))
          val filteredPathFinder = (p.base * "*") --- gitignorePathFinder
          for (f <- filteredPathFinder.get) {
            val target = activatorDistDirectory / p.id / f.name
            println("copy: " + target)
            IO.copyDirectory(f, target, overwrite = true,  preserveLastModified = true)
          }
          Dist.zip(activatorDistDirectory / p.id, activatorDistDirectory / (p.id + "-" + version + ".zip"))
        }
        
        activatorDistDirectory
      }
    }
  }

}
