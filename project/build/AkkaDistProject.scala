/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import sbt._

trait AkkaDistBaseProject extends DefaultProject {
  def distOutputPath: Path
  def distLibPath: Path
  def distSrcPath: Path
  def distDocPath: Path
  def dist: Task
}

trait AkkaDistProject extends AkkaDistBaseProject {
  def distName: String

  val distFullName = distName + "-" + version
  val distOutputBasePath = outputPath / "dist"
  val distOutputPath = (distOutputBasePath ##) / distFullName
  val distScalaLibPath = distOutputPath / "lib"
  val distBinPath = distOutputPath / "bin"
  val distConfigPath = distOutputPath / "config"
  val distDeployPath = distOutputPath / "deploy"
  val distLibPath = distOutputPath / "lib" / "akka"
  val distSrcPath = distOutputPath / "src" / "akka"
  val distDocPath = distOutputPath / "doc" / "akka"
  val distDocJarsPath = distDocPath / "api" / "jars"
  val distSharePath = Path.userHome / ".ivy2" / "dist" / distFullName
  val distArchiveName = distFullName + ".zip"
  val distArchive = (distOutputBasePath ##) / distArchiveName

  def distConfigSources = info.projectPath / "config" * "*"
  def distScriptSources = info.projectPath / "scripts" * "*"

  lazy val distExclusiveProperty = systemOptional[Boolean]("dist.exclusive", false)

  def distExclusive = distExclusiveProperty.value

  def scalaDependency = if (distExclusive) Path.emptyPathFinder else buildLibraryJar

  def allProjectDependencies = topologicalSort.dropRight(1)

  def distDependencies = {
    allProjectDependencies.flatMap( p => p match {
      case adp: AkkaDistBaseProject => Some(adp)
      case _ => None
    })
  }

  def distDependencyJarNames = {
    val jarNames = distDependencies.flatMap { dist =>
      (dist.distLibPath ** "*.jar").get.map(_.name) ++
      (dist.distSrcPath ** "*.jar").get.map(_.name) ++
      (dist.distDocPath ** "*.jar").get.map(_.name)
    }
    Set(jarNames: _*)
  }

  def distClasspath = runClasspath

  def filterOutExcludes(paths: PathFinder) = {
    if (distExclusive) {
      val exclude = distDependencyJarNames
      def include(path: Path) = !exclude(path.name)
      paths.filter(include)
    } else paths
  }

  def dependencyJars(filter: Path => Boolean) = distClasspath.filter(filter)

  def isJar(path: Path) = path.name.endsWith(".jar")

  def isSrcJar(path: Path) = isJar(path) && path.name.contains("-sources")

  def isDocJar(path: Path) = isJar(path) && path.name.contains("-docs")

  def isClassJar(path: Path) = isJar(path) && !isSrcJar(path) && !isDocJar(path)

  def projectDependencies = allProjectDependencies -- distDependencies

  def projectDependencyJars(f: PackagePaths => Path) = {
    Path.lazyPathFinder {
      projectDependencies.flatMap( p => p match {
        case pp: PackagePaths => Some(f(pp))
        case _ => None
      })
    }
  }

  def distLibs = filterOutExcludes(dependencyJars(isClassJar) +++ projectDependencyJars(_.jarPath))

  def distSrcJars = filterOutExcludes(dependencyJars(isSrcJar) +++ projectDependencyJars(_.packageSrcJar))

  def distDocJars = filterOutExcludes(dependencyJars(isDocJar) +++ projectDependencyJars(_.packageDocsJar))

  def distShareSources = (distOutputPath ##) ** "*"

  lazy val dist = (distAction dependsOn (distBase, `package`, packageSrc, packageDocs)
                   describedAs("Create a distribution."))

  def distAction = task {
    copyFiles(scalaDependency, distScalaLibPath) orElse
    copyFiles(distLibs, distLibPath) orElse
    copyFiles(distSrcJars, distSrcPath) orElse
    copyFiles(distDocJars, distDocJarsPath) orElse
    copyFiles(distConfigSources, distConfigPath) orElse
    copyScripts(distScriptSources, distBinPath) orElse
    copyPaths(distShareSources, distSharePath) orElse
    FileUtilities.zip(List(distOutputPath), distArchive, true, log)
  }

  lazy val distBase = distBaseAction dependsOn (distClean) describedAs "Create the dist base."

  def distBaseAction = task {
    if (!distExclusive) {
      distDependencies.map( dist => {
        val allFiles = (dist.distOutputPath ##) ** "*"
        copyPaths(allFiles, distOutputPath)
      }).foldLeft(None: Option[String])(_ orElse _)
    } else None
  }

  def distDependencyTasks: Seq[ManagedTask] = distDependencies.map(_.dist)

  lazy val distClean = (distCleanAction dependsOn (distDependencyTasks: _*)
                        describedAs "Clean the dist target dir.")

  def distCleanAction = task {
    FileUtilities.clean(distOutputPath, log) orElse
    FileUtilities.clean(distSharePath, log)
  }

  def copyFiles(from: PathFinder, to: Path): Option[String] = {
    if (from.get.isEmpty) None
    else FileUtilities.copyFlat(from.get, to, log).left.toOption
  }

  def copyPaths(from: PathFinder, to: Path): Option[String] = {
    if (from.get.isEmpty) None
    else FileUtilities.copy(from.get, to, log).left.toOption
  }

  def copyScripts(from: PathFinder, to: Path): Option[String] = {
    from.get.map { script =>
      val target = to / script.name
      FileUtilities.copyFile(script, target, log) orElse
      setExecutable(target, script.asFile.canExecute)
    }.foldLeft(None: Option[String])(_ orElse _)
  }

  def setExecutable(target: Path, executable: Boolean): Option[String] = {
    val success = target.asFile.setExecutable(executable, false)
    if (success) None else Some("Couldn't set permissions of " + target)
  }

  override def disableCrossPaths = true

  def doNothing = task { None }
  override def compileAction = doNothing
  override def testCompileAction = doNothing
  override def testAction = doNothing
  override def packageAction = doNothing
  override def publishLocalAction = doNothing
  override def deliverLocalAction = doNothing
  override def publishAction = doNothing
  override def deliverAction = doNothing
}

trait AkkaDistDocProject extends AkkaDistProject {
  def distDocName = distName

  def findDocParent(project: Project): DocParentProject = project.info.parent match {
    case Some(dpp: DocParentProject) => dpp
    case Some(p: Project) => findDocParent(p)
    case _ => error("Parent project is not a DocParentProject")
  }

  def docParent = findDocParent(this)

  override def distAction = super.distAction dependsOn (distApi, distRstDocs)

  val apiSources = (docParent.docOutputPath ##) ** "*"
  val apiPath = distDocPath / "api" / "html" / distDocName

  lazy val distApi = task {
    copyPaths(apiSources, apiPath)
  } dependsOn (distBase, docParent.doc)

  val rstDocsPath = docParent.info.projectPath / "akka-docs"

  lazy val rstDocs = task {
    import Process._
    log.info("Building docs...")
    val exitCode = ((new java.lang.ProcessBuilder("make", "clean", "html", "pdf")) directory rstDocsPath.asFile) ! log
    if (exitCode > 0) Some("Failed to build docs.") else None
  }

  val rstDocsBuildPath = rstDocsPath / "_build"
  val rstDocsHtmlSources = (rstDocsBuildPath / "html" ##) ** "*"
  val rstDocsPdfSources = (rstDocsBuildPath / "latex" ##) ** "*.pdf"

  val rstDocsOutputPath = distDocPath / "docs"
  val rstDocsHtmlPath = rstDocsOutputPath / "html" / distDocName
  val rstDocsPdfPath = rstDocsOutputPath / "pdf"

  lazy val distRstDocs = task {
    copyPaths(rstDocsHtmlSources, rstDocsHtmlPath) orElse
    copyPaths(rstDocsPdfSources, rstDocsPdfPath)
  } dependsOn (distBase, rstDocs)
}

/*
 * For wiring together akka and akka-modules.
 */
trait AkkaDistSharedProject extends AkkaDistBaseProject {
  def distName: String

  val distFullName = distName + "-" + version
  val distOutputPath = Path.userHome / ".ivy2" / "dist" / distFullName

  val distLibPath = distOutputPath / "lib" / "akka"
  val distSrcPath = distOutputPath / "src" / "akka"
  val distDocPath = distOutputPath / "doc" / "akka"

  lazy val dist = task { None }
}
