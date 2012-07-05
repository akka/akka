/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import sbt._

trait DistBaseProject extends DefaultProject {
  def distOutputPath: Path
  def distLibPath: Path
  def distSrcPath: Path
  def distDocPath: Path
  def dist: Task

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

trait DistProject extends DistBaseProject {
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

  val distExclusiveOutputBasePath = distOutputBasePath / "exclusive"
  val distExclusiveOutputPath = (distExclusiveOutputBasePath ##) / distFullName
  val distExclusiveArchive = (distExclusiveOutputBasePath ##) / distArchiveName

  def distConfigSources = ((info.projectPath / "config" ##) ***)
  def distScriptSources = ((info.projectPath / "scripts" ##) ***)

  def distAlwaysExclude(path: Path) = path.name == "scala-library.jar"
  def distAlwaysInclude(path: Path) = distConfigSources.get.toList.map(_.name).contains(path.name)

  def scalaDependency = buildLibraryJar

  def allProjectDependencies = topologicalSort.dropRight(1)

  def distDependencies = {
    allProjectDependencies.flatMap( p => p match {
      case adp: DistBaseProject => Some(adp)
      case _ => None
    })
  }

  def distClasspath = runClasspath

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

  def distLibs = dependencyJars(isClassJar) +++ projectDependencyJars(_.jarPath)

  def distSrcJars = dependencyJars(isSrcJar) +++ projectDependencyJars(_.packageSrcJar)

  def distDocJars = dependencyJars(isDocJar) +++ projectDependencyJars(_.packageDocsJar)

  def distShareSources = ((distOutputPath ##) ***)

  lazy val dist = (distAction dependsOn (distBase, `package`, packageSrc, packageDocs)
                   describedAs("Create a distribution."))

  def distAction = task {
    def exclusiveDist = {
      val excludePaths = (distDependencies.map(p => ((p.distOutputPath ##) ***))
                           .foldLeft(Path.emptyPathFinder)(_ +++ _))
      val excludeRelativePaths = excludePaths.get.toList.map(_.relativePath)
      val allDistPaths = ((distOutputPath ##) ***)
      val includePaths = allDistPaths.filter(path => {
        distAlwaysInclude(path) || !(distAlwaysExclude(path) || excludeRelativePaths.contains(path.relativePath))
      })
      copyPaths(includePaths, distExclusiveOutputPath) orElse
      FileUtilities.zip(List(distExclusiveOutputPath), distExclusiveArchive, true, log)
    }

    copyFiles(scalaDependency, distScalaLibPath) orElse
    copyFiles(distLibs, distLibPath) orElse
    copyFiles(distSrcJars, distSrcPath) orElse
    copyFiles(distDocJars, distDocJarsPath) orElse
    copyPaths(distConfigSources, distConfigPath) orElse
    copyScripts(distScriptSources, distBinPath) orElse
    copyPaths(distShareSources, distSharePath) orElse
    FileUtilities.zip(List(distOutputPath), distArchive, true, log) orElse
    exclusiveDist
  }

  lazy val distBase = distBaseAction dependsOn (distClean) describedAs "Create the dist base."

  def distBaseAction = task {
    distDependencies.map( dist => {
      val allFiles = ((dist.distOutputPath ##) ***)
      copyPaths(allFiles, distOutputPath)
    }).foldLeft(None: Option[String])(_ orElse _)
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
    else FileUtilities.copy(from.get, to, true, log).left.toOption
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
}

trait DistDocProject extends DistProject {
  def distDocName = distName

  def findDocParent(project: Project): DocParentProject = project.info.parent match {
    case Some(dpp: DocParentProject) => dpp
    case Some(p: Project) => findDocParent(p)
    case _ => error("Parent project is not a DocParentProject")
  }

  def docParent = findDocParent(this)

  override def distAction = super.distAction dependsOn (distApi, distDocs)

  val apiSources = ((docParent.apiOutputPath ##) ***)
  val apiPath = distDocPath / "api" / "html" / distDocName

  lazy val distApi = task {
    copyPaths(apiSources, apiPath)
  } dependsOn (distBase, docParent.api)

  val docsBuildPath = docParent.docsPath / "_build"
  val docsHtmlSources = ((docsBuildPath / "html" ##) ***)
  val docsPdfSources = (docsBuildPath / "latex" ##) ** "*.pdf"

  val docsOutputPath = distDocPath / "docs"
  val docsHtmlPath = docsOutputPath / "html" / distDocName
  val docsPdfPath = docsOutputPath / "pdf"

  lazy val distDocs = task {
    copyPaths(docsHtmlSources, docsHtmlPath) orElse
    copyPaths(docsPdfSources, docsPdfPath)
  } dependsOn (distBase, docParent.docs)
}

/*
 * For wiring together akka and akka-modules.
 */
trait DistSharedProject extends DistBaseProject {
  def distName: String

  val distFullName = distName + "-" + version
  val distOutputPath = Path.userHome / ".ivy2" / "dist" / distFullName

  val distLibPath = distOutputPath / "lib" / "akka"
  val distSrcPath = distOutputPath / "src" / "akka"
  val distDocPath = distOutputPath / "doc" / "akka"

  lazy val dist = task { None }
}
