/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import Process._
import Keys._

import java.io.File

object Protobuf {
  val paths = SettingKey[Seq[File]]("protobuf-paths", "The paths that contain *.proto files.")
  val outputPaths = SettingKey[Seq[File]]("protobuf-output-paths", "The paths where to save the generated *.java files.")
  val protoc = SettingKey[String]("protobuf-protoc", "The path and name of the protoc executable.")
  val protocVersion = SettingKey[String]("protobuf-protoc-version", "The version of the protoc executable.")
  val generate = TaskKey[Unit]("protobuf-generate", "Compile the protobuf sources and do all processing.")

  lazy val settings: Seq[Setting[_]] = Seq(
    paths <<= (sourceDirectory in Compile, sourceDirectory in Test) { (a, b) => Seq(a, b) map (_ / "protobuf") },
    outputPaths <<= (sourceDirectory in Compile, sourceDirectory in Test) { (a, b) => Seq(a, b) map (_ / "java") },
    protoc := "protoc",
    protocVersion := "2.5.0",
    generate <<= generateSourceTask
  )

  private def callProtoc[T](protoc: String, args: Seq[String], log: Logger, thunk: (ProcessBuilder, Logger) => T): T =
    try {
      val proc = Process(protoc, args)
      thunk(proc, log)
    } catch { case e: Exception =>
      throw new RuntimeException("error while executing '%s' with args: %s" format(protoc, args.mkString(" ")), e)
    }

  private def checkProtocVersion(protoc: String, protocVersion: String, log: Logger): Unit = {
    val res = callProtoc(protoc, Seq("--version"), log, { (p, l) => p !! l })
    val version = res.split(" ").last.trim
    if (version != protocVersion) {
      sys.error("Wrong protoc version! Expected %s but got %s" format (protocVersion, version))
    }
  }

  private def generate(protoc: String, srcDir: File, targetDir: File, log: Logger): Unit = {
    val protoFiles = (srcDir ** "*.proto").get
    if (srcDir.exists)
      if (protoFiles.isEmpty)
        log.info("Skipping empty source directory %s" format srcDir)
      else {
        targetDir.mkdirs()

        log.info("Generating %d protobuf files from %s to %s".format(protoFiles.size, srcDir, targetDir))
        protoFiles.foreach { proto => log.info("Compiling %s" format proto) }

        val exitCode = callProtoc(protoc, Seq("-I" + srcDir.absolutePath, "--java_out=%s" format targetDir.absolutePath) ++
          protoFiles.map(_.absolutePath), log, { (p, l) => p ! l })
        if (exitCode != 0)
          sys.error("protoc returned exit code: %d" format exitCode)
      }
  }

  private def generateSourceTask: Project.Initialize[Task[Unit]] = (streams, paths, outputPaths, protoc, protocVersion) map {
    (out, sourceDirs, targetDirs, protoc, protocVersion) =>
      if (sourceDirs.size != targetDirs.size)
        sys.error("Unbalanced number of paths and destination paths!\nPaths: %s\nDestination Paths: %s" format
          (sourceDirs, targetDirs))

      if (sourceDirs exists { _.exists }) {
        checkProtocVersion(protoc, protocVersion, out.log)

        (sourceDirs zip targetDirs) map {
          case (sourceDir, targetDir) =>
            generate(protoc, sourceDir, targetDir, out.log)
        }
      }
  }
}
