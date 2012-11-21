package akka

import sbt._
import sbt.Keys._
import sbt.classpath.ClasspathUtilities
import sbt.Project.Initialize
import java.io.File
import com.typesafe.sbt.site.SphinxSupport.{ generate, Sphinx }

object Dist {
  case class DistSources(depJars: Seq[File], libJars: Seq[File], srcJars: Seq[File], docJars: Seq[File], api: File, docs: File)

  val distExclude = SettingKey[Seq[String]]("dist-exclude")
  val distAllClasspaths = TaskKey[Seq[Classpath]]("dist-all-classpaths")
  val distDependencies = TaskKey[Seq[File]]("dist-dependencies")
  val distLibJars = TaskKey[Seq[File]]("dist-lib-jars")
  val distSrcJars = TaskKey[Seq[File]]("dist-src-jars")
  val distDocJars = TaskKey[Seq[File]]("dist-doc-jars")
  val distSources = TaskKey[DistSources]("dist-sources")
  val distDirectory = SettingKey[File]("dist-directory")
  val distUnzipped = SettingKey[File]("dist-unzipped")
  val distFile = SettingKey[File]("dist-file")
  val dist = TaskKey[File]("dist", "Create a zipped distribution of everything.")

  lazy val settings: Seq[Setting[_]] = Seq(
    distExclude := Seq.empty,
    distAllClasspaths <<= (thisProjectRef, buildStructure, distExclude) flatMap aggregated(dependencyClasspath.task in Compile),
    distDependencies <<= distAllClasspaths map { _.flatten.map(_.data).filter(ClasspathUtilities.isArchive).distinct },
    distLibJars <<= (thisProjectRef, buildStructure, distExclude) flatMap aggregated(packageBin.task in Compile),
    distSrcJars <<= (thisProjectRef, buildStructure, distExclude) flatMap aggregated(packageSrc.task in Compile),
    distDocJars <<= (thisProjectRef, buildStructure, distExclude) flatMap aggregated(packageDoc.task in Compile),
    distSources <<= (distDependencies, distLibJars,  distSrcJars, distDocJars, Unidoc.unidoc, generate in Sphinx in docsProject) map DistSources,
    distDirectory <<= crossTarget / "dist",
    distUnzipped <<= distDirectory / "unzipped",
    distFile <<= (distDirectory, version) { (dir, v) => dir / ("akka-" + v + ".zip") },
    dist <<= distTask
  )

  def docsProject: ProjectReference = LocalProject(AkkaBuild.docs.id)

  def aggregated[T](task: SettingKey[Task[T]])(projectRef: ProjectRef, structure: Load.BuildStructure, exclude: Seq[String]): Task[Seq[T]] = {
    val projects = aggregatedProjects(projectRef, structure, exclude)
    projects flatMap { task in LocalProject(_) get structure.data } join
  }

  def aggregatedProjects(projectRef: ProjectRef, structure: Load.BuildStructure, exclude: Seq[String]): Seq[String] = {
    val aggregate = Project.getProject(projectRef, structure).toSeq.flatMap(_.aggregate)
    aggregate flatMap { ref =>
      if (exclude contains ref.project) Seq.empty
      else ref.project +: aggregatedProjects(ref, structure, exclude)
    }
  }

  def distTask: Initialize[Task[File]] = {
    (baseDirectory, distSources, distUnzipped, version, distFile, streams) map {
      (projectBase, allSources, unzipped, version, zipFile, s) => {
        val base = unzipped / ("akka-" + version)
        val distBase = projectBase / "akka-kernel" / "src" / "main" / "dist"
        val deploy = base / "deploy"
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
    val sources = (source ***) x rebase(source, target)
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
    val sources = files x relativeTo(source)
    IO.zip(sources, target)
    target
  }
}
