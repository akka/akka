package akka

import sbt._
import sbt.Keys._
import sbt.Project.Initialize

object Unidoc {

  lazy val JavaDoc = config("genjavadoc") extend Compile

  lazy val javadocSettings = inConfig(JavaDoc)(Defaults.configSettings) ++ Seq(
    libraryDependencies += Dependencies.Compile.genjavadoc,
    scalacOptions <+= target map (t => "-P:genjavadoc:out=" + t + "/java"),
    packageDoc in Compile <<= packageDoc in JavaDoc,
    sources in JavaDoc <<= (target, compile in Compile, sources in Compile) map ((t, c, s) => (t / "java" ** "*.java").get ++ s.filter(_.getName.endsWith(".java"))),
    javacOptions in JavaDoc := Seq(),
    artifactName in packageDoc in JavaDoc := ((sv, mod, art) => "" + mod.name + "_" + sv.binary + "-" + mod.revision + "-javadoc.jar")
  )

  val unidocDirectory = SettingKey[File]("unidoc-directory")
  val unidocExclude = SettingKey[Seq[String]]("unidoc-exclude")
  val unidocAllSources = TaskKey[Seq[Seq[File]]]("unidoc-all-sources")
  val unidocSources = TaskKey[Seq[File]]("unidoc-sources")
  val unidocAllClasspaths = TaskKey[Seq[Classpath]]("unidoc-all-classpaths")
  val unidocClasspath = TaskKey[Seq[File]]("unidoc-classpath")
  val unidoc = TaskKey[File]("unidoc", "Create unified scaladoc for all aggregates")
  val junidocAllSources = TaskKey[Seq[Seq[File]]]("junidoc-all-sources")
  val junidocSources = TaskKey[Seq[File]]("junidoc-sources")

  lazy val settings = Seq(
    unidocDirectory <<= crossTarget / "unidoc",
    unidocExclude := Seq.empty,
    unidocAllSources <<= (thisProjectRef, buildStructure, unidocExclude) flatMap allSources(Compile),
    unidocSources <<= unidocAllSources map { _.flatten },
    unidocAllClasspaths <<= (thisProjectRef, buildStructure, unidocExclude) flatMap allClasspaths,
    unidocClasspath <<= unidocAllClasspaths map { _.flatten.map(_.data).distinct },
    junidocAllSources <<= (thisProjectRef, buildStructure, unidocExclude) flatMap allSources(JavaDoc),
    junidocSources <<= junidocAllSources map { _.flatten },
    unidoc <<= unidocTask
  )

  def allSources(conf: Configuration)(projectRef: ProjectRef, structure: Load.BuildStructure, exclude: Seq[String]): Task[Seq[Seq[File]]] = {
    val projects = aggregated(projectRef, structure, exclude)
    projects flatMap { sources in conf in LocalProject(_) get structure.data } join
  }

  def allClasspaths(projectRef: ProjectRef, structure: Load.BuildStructure, exclude: Seq[String]): Task[Seq[Classpath]] = {
    val projects = aggregated(projectRef, structure, exclude)
    projects flatMap { dependencyClasspath in Compile in LocalProject(_) get structure.data } join
  }

  def aggregated(projectRef: ProjectRef, structure: Load.BuildStructure, exclude: Seq[String]): Seq[String] = {
    val aggregate = Project.getProject(projectRef, structure).toSeq.flatMap(_.aggregate)
    aggregate flatMap { ref =>
      if (exclude contains ref.project) Seq.empty
      else ref.project +: aggregated(ref, structure, exclude)
    }
  }

  def unidocTask: Initialize[Task[File]] = {
    (compilers, cacheDirectory, unidocSources, unidocClasspath, unidocDirectory, scalacOptions in doc, streams) map {
      (compilers, cache, sources, classpath, target, options, s) => {
        val scaladoc = new Scaladoc(100, compilers.scalac)
        scaladoc.cached(cache / "unidoc", "main", sources, classpath, target, options, s.log)
        target
      }
    }
  }
}
