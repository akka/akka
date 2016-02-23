/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import sbt.Def.Initialize
import java.io.File
import sbt.Task

object ActivatorDist {

  val activatorDistDirectory = SettingKey[File]("activator-dist-directory")
  val activatorDist = TaskKey[File]("activator-dist", "Create a zipped distribution of each activator sample.")

  lazy val settings: Seq[Setting[_]] = Seq(
    activatorDistDirectory <<= crossTarget / "activator-dist",
    activatorDist <<= activatorDistTask
  )

  def activatorDistTask: Initialize[Task[File]] = {
    (thisProjectRef, baseDirectory, activatorDistDirectory, version, buildStructure, streams) map {
      (project, projectBase, activatorDistDirectory, version, structure, s) => {
        val directories = projectBase.listFiles(DirectoryFilter).filter(dir => (dir / "activator.properties").exists)
        val rootGitignoreLines = IO.readLines(AkkaBuild.root.base / ".gitignore")
        for (dir <- directories) {
         val localGitignoreLines = if ((dir / ".gitignore").exists) IO.readLines(dir / ".gitignore") else Nil
         val gitignoreFileFilter = (".gitignore" :: localGitignoreLines ::: rootGitignoreLines).
           foldLeft[FileFilter](NothingFilter)((acc, x) => acc || x)
          val filteredPathFinder = PathFinder(dir) descendantsExcept("*", gitignoreFileFilter) filter(_.isFile)
          filteredPathFinder pair Path.rebase(dir, activatorDistDirectory / dir.name) map {
            case (source, target) =>
              s.log.info(s"copying: $source -> $target")
              IO.copyFile(source, target, preserveLastModified = true)
          }
          val targetDir = activatorDistDirectory / dir.name
          val targetFile = activatorDistDirectory / (dir.name + "-" + version + ".zip")
          s.log.info(s"zipping: $targetDir -> $targetFile")
          Dist.zip(targetDir, targetFile)
        }

        activatorDistDirectory
      }
    }
  }

}
