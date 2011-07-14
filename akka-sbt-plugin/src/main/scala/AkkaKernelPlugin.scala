/**
 *  Copyright (C) 2011 Typesafe <http://typesafe.com/>
 */

import sbt._
import sbt.Keys._
import sbt.classpath.ClasspathUtilities
import sbt.Project.Initialize
import java.io.File

object AkkaMicrokernelPlugin extends Plugin {

  val Dist = config("dist") extend (Runtime)
  val dist = TaskKey[File]("dist", "Builds an Akka microkernel directory")
  // TODO how to reuse keyword "clean" here instead (dist:clean)
  val distClean = TaskKey[File]("clean-dist", "Removes Akka microkernel directory")
  val outputDirectory = SettingKey[File]("output-directory")
  val configSourceDirs = TaskKey[Seq[File]]("config-source-directories",
    "Configuration files are copied from these directories")

  val distJvmOptions = SettingKey[String]("jvm-options", "JVM parameters to use in start script")
  val distMainClass = SettingKey[String]("kernel-main-class", "Kernel main class to use in start script")

  val libFilter = SettingKey[File ⇒ Boolean]("lib-filter", "Filter of dependency jar files")
  val additionalLibs = TaskKey[Seq[File]]("additional-libs", "Additional dependency jar files")

  override lazy val settings =
    inConfig(Dist)(Seq(
      dist <<= packageBin.identity,
      packageBin <<= distTask,
      distClean <<= distCleanTask,
      dependencyClasspath <<= (dependencyClasspath in Runtime).identity,
      unmanagedResourceDirectories <<= (unmanagedResourceDirectories in Runtime).identity,
      outputDirectory <<= target / "dist",
      configSourceDirs <<= defaultConfigSourceDirs,
      distJvmOptions := "-Xms1024M -Xmx1024M -Xss1M -XX:MaxPermSize=256M -XX:+UseParallelGC",
      distMainClass := "akka.kernel.Main",
      libFilter := { f ⇒ true },
      additionalLibs <<= defaultAdditionalLibs)) ++
      Seq(
        dist <<= (dist in Dist).identity)

  private def distTask: Initialize[Task[File]] =
    (outputDirectory, sourceDirectory, crossTarget, dependencyClasspath,
      configSourceDirs, distJvmOptions, distMainClass, libFilter, streams) map { 
          (outDir, src, tgt, cp, configSrc, jvmOptions, mainClass, libFilt, s) ⇒
        val log = s.log
        val distBinPath = outDir / "bin"
        val distConfigPath = outDir / "config"
        val distDeployPath = outDir / "deploy"
        val distLibPath = outDir / "lib"
        // TODO how do I grab the additionalLibs setting? Can't add it in input tuple, limitation of number of elements in map of tuple.
        val addLibs = Seq.empty[File]

        log.info("Creating distribution %s ..." format outDir)
        IO.createDirectory(outDir)
        Scripts(jvmOptions, mainClass).writeScripts(distBinPath)
        copyDirectories(configSrc, distConfigPath)
        copyJars(tgt, distDeployPath)
        copyFiles(libFiles(cp, libFilt), distLibPath)
        copyFiles(addLibs, distLibPath)
        log.info("Distribution created.")
        outDir
      }

  private def distCleanTask: Initialize[Task[File]] =
    (outputDirectory, streams) map { (outDir, s) ⇒
      val log = s.log
      log.info("Cleaning " + outDir)
      IO.delete(outDir)
      outDir
    }

  def defaultConfigSourceDirs = (sourceDirectory, unmanagedResourceDirectories) map { (src, resources) ⇒
    Seq(src / "main" / "config") ++ resources
  }

  def defaultAdditionalLibs = (libraryDependencies) map { (libs) ⇒
    Seq.empty[File]
  }

  private case class Scripts(jvmOptions: String, mainClass: String) {

    def writeScripts(to: File) = {
      scripts.map { script ⇒
        val target = new File(to, script.name)
        IO.write(target, script.contents)
        setExecutable(target, script.executable)
      }.foldLeft(None: Option[String])(_ orElse _)
    }

    private case class DistScript(name: String, contents: String, executable: Boolean)

    private def scripts = Set(DistScript("start", distShScript, true),
      DistScript("start.bat", distBatScript, true))

    private def distShScript =
      """|#!/bin/sh
    |
    |AKKA_HOME="$(cd "$(cd "$(dirname "$0")"; pwd -P)"/..; pwd)"
    |AKKA_CLASSPATH="$AKKA_HOME/lib/*:$AKKA_HOME/config"
    |JAVA_OPTS="%s"
    |
    |java $JAVA_OPTS -cp "$AKKA_CLASSPATH" -Dakka.home="$AKKA_HOME" %s
    |""".stripMargin.format(jvmOptions, mainClass)

    private def distBatScript =
      """|@echo off
    |set AKKA_HOME=%%~dp0..
    |set AKKA_CLASSPATH=%%AKKA_HOME%%\lib\*;%%AKKA_HOME%%\config
    |set JAVA_OPTS=%s
    |
    |java %%JAVA_OPTS%% -cp "%%AKKA_CLASSPATH%%" -Dakka.home="%%AKKA_HOME%%" %s
    |""".stripMargin.format(jvmOptions, mainClass)

    private def setExecutable(target: File, executable: Boolean): Option[String] = {
      val success = target.setExecutable(executable, false)
      if (success) None else Some("Couldn't set permissions of " + target)
    }
  }

  private def copyDirectories(fromDirs: Seq[File], to: File) = {
    IO.createDirectory(to)
    for (from ← fromDirs) {
      IO.copyDirectory(from, to)
    }
  }

  private def copyJars(fromDir: File, toDir: File) = {
    val jarFiles = fromDir.listFiles.filter(f ⇒
      f.isFile &&
        f.name.endsWith(".jar") &&
        !f.name.contains("-sources") &&
        !f.name.contains("-docs"))

    copyFiles(jarFiles, toDir)
  }

  private def copyFiles(files: Seq[File], toDir: File) = {
    for (f ← files) {
      IO.copyFile(f, new File(toDir, f.getName))
    }
  }

  private def libFiles(classpath: Classpath, libFilter: File ⇒ Boolean): Seq[File] = {
    val (libs, directories) = classpath.map(_.data).partition(ClasspathUtilities.isArchive)
    libs.map(_.asFile).filter(libFilter)
  }

}

