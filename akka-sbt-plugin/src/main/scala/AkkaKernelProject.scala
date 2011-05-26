/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import sbt._

trait AkkaKernelProject extends AkkaProject with AkkaMicrokernelProject {
  // automatic akka kernel dependency
  val akkaKernel = akkaModule("kernel")
}

trait AkkaMicrokernelProject extends AkkaConfigProject {
  def distOutputPath = outputPath / "dist"

  def distBinName = "bin"
  def distConfigName = "config"
  def distDeployName = "deploy"
  def distLibName = "lib"

  def distBinPath = distOutputPath / distBinName
  def distConfigPath = distOutputPath / distConfigName
  def distDeployPath = distOutputPath / distDeployName
  def distLibPath = distOutputPath / distLibName

  def distJvmOptions = "-Xms1024M -Xmx1024M -Xss1M -XX:MaxPermSize=256M -XX:+UseParallelGC"
  def distMainClass = "akka.kernel.Main"

  def distProjectDependencies = topologicalSort.dropRight(1)

  def distProjectDependenciesConfig = {
    distProjectDependencies.flatMap( p => p match {
      case acp: AkkaConfigProject => Some(acp.configSources)
      case _ => None
    }).foldLeft(Path.emptyPathFinder)(_ +++ _)
  }

  def distConfigSources = configSources +++ distProjectDependenciesConfig

  def distDeployJars = jarPath

  def distRuntimeJars = {
    runClasspath
    .filter(ClasspathUtilities.isArchive)
    .filter(jar => !jar.name.contains("-sources"))
    .filter(jar => !jar.name.contains("-docs"))
  }

  def distProjectDependencyJars = jarsOfProjectDependencies

  def distLibs = distRuntimeJars +++ distProjectDependencyJars +++ buildLibraryJar

  lazy val dist = (distAction dependsOn (`package`, distClean)
                   describedAs "Create an Akka microkernel distribution.")

  def distAction = task {
    log.info("Creating distribution %s ..." format distOutputPath)
    writeScripts(distScripts, distBinPath) orElse
    copyFiles(distConfigSources, distConfigPath) orElse
    copyFiles(distDeployJars, distDeployPath) orElse
    copyFiles(distLibs, distLibPath) orElse {
      log.info("Distribution created.")
      None
    }
  }

  def copyFiles(from: PathFinder, to: Path) = {
    FileUtilities.copyFlat(from.get, to, log).left.toOption
  }

  lazy val distClean = distCleanAction describedAs "Clean the dist target dir."

  def distCleanAction = task { FileUtilities.clean(distOutputPath, log) }

  case class DistScript(name: String, contents: String, executable: Boolean)

  def distScripts = Set(DistScript("start", distShScript, true),
                        DistScript("start.bat", distBatScript, true))

  def distShScript = """|#!/bin/sh
                        |
                        |AKKA_HOME="$(cd "$(cd "$(dirname "$0")"; pwd -P)"/..; pwd)"
                        |AKKA_CLASSPATH="$AKKA_HOME/lib/*:$AKKA_HOME/config"
                        |JAVA_OPTS="%s"
                        |
                        |java $JAVA_OPTS -cp "$AKKA_CLASSPATH" -Dakka.home="$AKKA_HOME" %s
                        |""".stripMargin.format(distJvmOptions, distMainClass)

  def distBatScript = """|@echo off
                         |set AKKA_HOME=%%~dp0..
                         |set AKKA_CLASSPATH=%%AKKA_HOME%%\lib\*;%%AKKA_HOME%%\config
                         |set JAVA_OPTS=%s
                         |
                         |java %%JAVA_OPTS%% -cp "%%AKKA_CLASSPATH%%" -Dakka.home="%%AKKA_HOME%%" %s
                         |""".stripMargin.format(distJvmOptions, distMainClass)

  def writeScripts(scripts: Set[DistScript], to: Path) = {
    scripts.map { script =>
      val target = to / script.name
      FileUtilities.write(target.asFile, script.contents, log) orElse
      setExecutable(target, script.executable)
    }.foldLeft(None: Option[String])(_ orElse _)
  }

  def setExecutable(target: Path, executable: Boolean): Option[String] = {
    val success = target.asFile.setExecutable(executable, false)
    if (success) None else Some("Couldn't set permissions of " + target)
  }
}

trait AkkaConfigProject extends BasicScalaProject with MavenStyleScalaPaths {
  def mainConfigPath = mainSourcePath / "config"

  def configSources = mainConfigPath ** "*.*"

  override def mainUnmanagedClasspath = super.mainUnmanagedClasspath +++ mainConfigPath
}
