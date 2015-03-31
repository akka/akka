/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import java.io.File

import sbt._

object Maven {
  val mvn = config("maven")

  val projects = settingKey[Seq[File]]("Projects which should be built by calling maven")
  val defaultCommands = settingKey[Seq[String]]("Commands to be called on each maven project")
  val javaHomeOverride = settingKey[Option[File]]("JAVA_HOME to be used while building projects with maven")
  val mavenLocalRepoOverride = settingKey[Option[File]]("Overrides local maven repository location if set")

  val mvnExec = taskKey[Unit]("Test all maven projects (as listed in projectsToTest)")

  lazy val settings: Seq[Setting[_]] = Seq(
    projects in mvn := Nil,
    defaultCommands in mvn := "clean" :: "test" :: Nil,
    javaHomeOverride in mvn := AkkaBuild.java8Home.value,
    mvnExec := {
      val javaHome = (javaHomeOverride in mvn).value
      val prjs = (projects in mvn).value
      val cmnds = (defaultCommands in mvn).value
      prjs foreach { p ⇒ mvnRun(javaHome, p.getAbsolutePath, cmnds: _*) }
    }
  )

  /** Runs maven commands (blocking) on given project directory (by cd-ing into it) */
  def mvnRun(proj: String, commands: String*): Unit =
    mvnRun(None, proj, commands:_ *)

  /** Runs maven commands (blocking) on given project directory (by cd-ing into it) */
  def mvnRun(javaHome: Option[File], proj: String, commands: String*): Unit = {
    val exportJHome = javaHome.map(h ⇒ s"""export JAVA_HOME="$h"; """).getOrElse("")
    val initialCmd = s"${exportJHome}cd $proj; mvn "

    val cmd = List("sh", "-c", commands.mkString(initialCmd, " ", ""))
    if ((cmd.!(MavenLogger)) != 0) throw new Exception(s"Failed building [$proj] using maven!")
  }

  object MavenLogger extends sbt.ProcessLogger {
    override def info(s: ⇒ String): Unit = println("[maven-out] " + s)
    override def error(s: ⇒ String): Unit = System.err.println("[maven-err] " + s)
    override def buffer[T](f: ⇒ T): T = f
  }

}
