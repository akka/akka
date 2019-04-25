/*
 * Copyright (C) 2011-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package com.typesafe.sbt.multijvm

import java.io.File
import java.lang.{ProcessBuilder => JProcessBuilder}

import sbt._
import com.typesafe.sbt.multijvm.Compat.{Process, _}

object Jvm {
  def startJvm(javaBin: File, jvmOptions: Seq[String], runOptions: Seq[String], logger: Logger, connectInput: Boolean) = {
    forkJava(javaBin, jvmOptions ++ runOptions, logger, connectInput)
  }

  def forkJava(javaBin: File, options: Seq[String], logger: Logger, connectInput: Boolean) = {
    val java = javaBin.toString
    val command = (java :: options.toList).toArray
    val builder = new JProcessBuilder(command: _*)
    Process(builder).run(logger, connectInput)
  }

  /**
   * check if the current operating system is some OS
  **/
  def isOS(os:String) = try {
    System.getProperty("os.name").toUpperCase startsWith os.toUpperCase
  } catch {
    case _ : Throwable => false
  }

  /**
   * convert to proper path for the operating system
  **/
  def osPath(path:String) = if (isOS("WINDOWS")) Process(Seq("cygpath", path)).lineStream.mkString else path

  def syncJar(jarName: String, hostAndUser: String, remoteDir: String, sbtLogger: Logger) : Process = {
    val command: Array[String] = Array("ssh", hostAndUser, "mkdir -p " + remoteDir)
    val builder = new JProcessBuilder(command: _*)
    sbtLogger.debug("Jvm.syncJar about to run " + command.mkString(" "))
    val process = Process(builder).run(sbtLogger, false)
    if (process.exitValue() == 0) {
      val command: Array[String] = Array("rsync", "-ace", "ssh", osPath(jarName), hostAndUser +":" + remoteDir +"/")
      val builder = new JProcessBuilder(command: _*)
      sbtLogger.debug("Jvm.syncJar about to run " + command.mkString(" "))
      Process(builder).run(sbtLogger, false)
    }
    else {
      process
    }
  }

  def forkRemoteJava(java: String, jvmOptions: Seq[String], appOptions: Seq[String], jarName: String,
                     hostAndUser: String, remoteDir: String, logger: Logger, connectInput: Boolean,
                     sbtLogger: Logger): Process = {
    sbtLogger.debug("About to use java " + java)
    val shortJarName = new File(jarName).getName
    val javaCommand = List(List(java), jvmOptions, List("-cp", shortJarName), appOptions).flatten
    val command = Array("ssh", hostAndUser, ("cd " :: (remoteDir :: (" ; " :: javaCommand))).mkString(" "))
    sbtLogger.debug("Jvm.forkRemoteJava about to run " + command.mkString(" "))
    val builder = new JProcessBuilder(command: _*)
    Process(builder).run(logger, connectInput)
  }
}

class JvmBasicLogger(name: String) extends BasicLogger {
  def jvm(message: String) = "[%s] %s" format (name, message)

  def log(level: Level.Value, message: => String) = System.out.synchronized {
    System.out.println(jvm(message))
  }

  def trace(t: => Throwable) = System.out.synchronized {
    val traceLevel = getTrace
    if (traceLevel >= 0) System.out.print(StackTrace.trimmed(t, traceLevel))
  }

  def success(message: => String) = log(Level.Info, message)
  def control(event: ControlEvent.Value, message: => String) = log(Level.Info, message)

  def logAll(events: Seq[LogEvent]) = System.out.synchronized { events.foreach(log) }
}

final class JvmLogger(name: String) extends JvmBasicLogger(name)
