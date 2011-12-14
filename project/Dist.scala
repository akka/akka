package akka

import sbt._
import sbt.Keys._
import sbt.classpath.ClasspathUtilities
import sbt.Project.Initialize
import java.io.File

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
    distSources <<= (distDependencies, distLibJars,  distSrcJars, distDocJars, Unidoc.unidoc, Rstdoc.rstdoc) map DistSources,
    distDirectory <<= crossTarget / "dist",
    distUnzipped <<= distDirectory / "unzipped",
    distFile <<= (distDirectory, version) { (dir, v) => dir / ("akka-" + v + ".zip") },
    dist <<= distTask
  )

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
        val scripts = (projectBase / "scripts" / "microkernel" * "*").get
        val bin = base / "bin"
        val configSources = projectBase / "config"
        val config = base / "config"
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
        // TODO: re-enable bin and config dirs, and add deploy dir, when akka-kernel is enabled
        //copyFilesTo(scripts, bin, setExecutable = true)
        //IO.copyDirectory(configSources, config)
        IO.copyDirectory(allSources.api, api)
        IO.copyDirectory(allSources.docs, docs)
        copyFilesTo(allSources.docJars, docJars)
        copyFilesTo(scalaLibs, lib)
        copyFilesTo(akkaLibs, libAkka)
        copyFilesTo(allSources.srcJars, src)
        val files = unzipped ** -DirectoryFilter
        val sources = files x relativeTo(unzipped)
        IO.zip(sources, zipFile)
        zipFile
      }
    }
  }

  def copyFilesTo(files: Seq[File], dir: File, setExecutable: Boolean = false): Unit = {
    IO.createDirectory(dir)
    for (file <- files) {
      val target = dir / file.name
      IO.copyFile(file, target)
      if (setExecutable) target.setExecutable(file.canExecute, false)
    }
  }
}
