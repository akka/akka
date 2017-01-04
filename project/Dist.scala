/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import sbt.classpath.ClasspathUtilities
import java.io.File
import com.typesafe.sbt.site.SphinxSupport.{ generate, Sphinx }
import sbtunidoc.Plugin._

object Dist {
  case class DistSources(depJars: Seq[File], libJars: Seq[File], srcJars: Seq[File], docJars: Seq[File], api: File, docs: File)

  val distDirectory = SettingKey[File]("dist-directory")
  val distUnzipped = SettingKey[File]("dist-unzipped")
  val distFile = SettingKey[File]("dist-file")

  val distAllClasspaths = TaskKey[Seq[Classpath]]("dist-all-classpaths")
  val distDependencies = TaskKey[Seq[File]]("dist-dependencies")
  val distLibJars = TaskKey[Seq[File]]("dist-lib-jars")
  val distSrcJars = TaskKey[Seq[File]]("dist-src-jars")
  val distDocJars = TaskKey[Seq[File]]("dist-doc-jars")
  val distSources = TaskKey[DistSources]("dist-sources")
  val dist = TaskKey[File]("dist", "Create a zipped distribution of everything.")
  val includeInDist = SettingKey[Boolean]("include-in-dist", "Include the artifact of this project in the standalone dist zip-file")

  lazy val settings: Seq[Setting[_]] = Seq(
    distAllClasspaths <<= (thisProjectRef, buildStructure) flatMap aggregated(dependencyClasspath in Compile),
    distDependencies <<= distAllClasspaths map { _.flatten.map(_.data).filter(ClasspathUtilities.isArchive).distinct },
    distLibJars <<= (thisProjectRef, buildStructure) flatMap aggregated(packageBin in Compile),
    distSrcJars <<= (thisProjectRef, buildStructure) flatMap aggregated(packageSrc in Compile),
    distDocJars <<= (thisProjectRef, buildStructure) flatMap aggregated(packageDoc in Compile),
    distSources <<= (distDependencies, distLibJars,  distSrcJars, distDocJars, doc in ScalaUnidoc, generate in Sphinx in docsProject) map DistSources,
    distDirectory <<= crossTarget / "dist",
    distUnzipped <<= distDirectory / "unzipped",
    distFile <<= (distDirectory, version, scalaBinaryVersion) { (dir, v, sbv) =>
      dir / ("akka_" + sbv + "-" + v + ".zip") },
    dist <<= distTask
  )

  def docsProject: ProjectReference = LocalProject(AkkaBuild.docs.id)

  def aggregated[T](task: TaskKey[T])(projectRef: ProjectRef, structure: BuildStructure): Task[Seq[T]] = {
    val projects = aggregatedProjects(projectRef, structure, task.scope)
    projects flatMap { task in _ get structure.data } join
  }

  def aggregatedProjects(projectRef: ProjectRef, structure: BuildStructure, scope: Scope): Seq[ProjectRef] = {
    val aggregate = Project.getProject(projectRef, structure).toSeq.flatMap(_.aggregate)
    aggregate flatMap { ref =>
      if (!(includeInDist in ref in scope get structure.data getOrElse false)) Nil
      else ref +: aggregatedProjects(ref, structure, scope)
    }
  }

  def distTask: Def.Initialize[Task[File]] = {
    (baseDirectory, distSources, distUnzipped, version, distFile, streams) map {
      (projectBase, allSources, unzipped, version, zipFile, s) => {
        val base = unzipped / ("akka-" + version)
        val distBase = projectBase / "akka-kernel" / "src" / "main" / "dist"
        val doc = base / "doc" / "akka"
        val api = doc / "api"
        val docs = doc / "docs"
        val docJars = doc / "jars"
        val libs = allSources.depJars ++ allSources.libJars
        val (scalaLibs, akkaLibs) = libs partition (_.name.contains("scala-library"))
        val lib = base / "lib"
        val libAkka = lib / "akka"
        val src = base / "src" / "akka"
        IO.delete(unzipped)
        copyDirectory(distBase, base, setExecutable = true)
        copyDirectory(allSources.api, api)
        copyDirectory(allSources.docs, docs)
        copyFlat(allSources.docJars, docJars)
        copyFlat(scalaLibs, lib)
        copyFlat(akkaLibs, libAkka)
        copyFlat(allSources.srcJars, src)
        zip(unzipped, zipFile)
      }
    }
  }

  def copyDirectory(source: File, target: File, overwrite: Boolean = false, preserveLastModified: Boolean = false, setExecutable: Boolean = false): Set[File] = {
    val sources = (source ***) pair rebase(source, target)
    copyMapped(sources, overwrite, preserveLastModified, setExecutable)
  }

  def copyFlat(files: Seq[File], target: File, overwrite: Boolean = false, preserveLastModified: Boolean = false, setExecutable: Boolean = false): Set[File] = {
    IO.createDirectory(target)
    val sources = files map { f => (f, target / f.name) }
    copyMapped(sources, overwrite, preserveLastModified, setExecutable)
  }

  def copyMapped(sources: Traversable[(File, File)], overwrite: Boolean, preserveLastModified: Boolean, setExecutable: Boolean): Set[File] = {
    sources map { Function.tupled(copy(overwrite, preserveLastModified, setExecutable)) } toSet
  }

  def copy(overwrite: Boolean, preserveLastModified: Boolean, setExecutable: Boolean)(source: File, target: File): File = {
    if (overwrite || !target.exists || source.lastModified > target.lastModified) {
      if (source.isDirectory) IO.createDirectory(target)
      else {
        IO.createDirectory(target.getParentFile)
        IO.copyFile(source, target, preserveLastModified)
        if (setExecutable) target.setExecutable(source.canExecute, false)
      }
    }
    target
  }

  def zip(source: File, target: File): File = {
    val files = source ** -DirectoryFilter
    val sources = files pair relativeTo(source)
    IO.zip(sources, target)
    target
  }
}
