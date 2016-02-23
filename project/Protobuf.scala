/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka

import sbt._
import Process._
import Keys._
import com.typesafe.sbt.preprocess.Preprocess._

import java.io.File

object Protobuf {
  val paths = SettingKey[Seq[File]]("protobuf-paths", "The paths that contain *.proto files.")
  val outputPaths = SettingKey[Seq[File]]("protobuf-output-paths", "The paths where to save the generated *.java files.")
  val protoc = SettingKey[String]("protobuf-protoc", "The path and name of the protoc executable.")
  val protocVersion = SettingKey[String]("protobuf-protoc-version", "The version of the protoc executable.")
  val generate = TaskKey[Unit]("protobuf-generate", "Compile the protobuf sources and do all processing.")

  lazy val settings: Seq[Setting[_]] = Seq(
    paths := Seq((sourceDirectory in Compile).value, (sourceDirectory in Test).value).map(_ / "protobuf"),
    outputPaths := Seq((sourceDirectory in Compile).value, (sourceDirectory in Test).value).map(_ / "java"),
    protoc := "protoc",
    protocVersion := "2.5.0",
    generate := {
      val sourceDirs = paths.value
      val targetDirs = outputPaths.value

      if (sourceDirs.size != targetDirs.size)
        sys.error(s"Unbalanced number of paths and destination paths!\nPaths: $sourceDirs\nDestination Paths: $targetDirs")

      if (sourceDirs exists (_.exists)) {
        val cmd = protoc.value
        val log = streams.value.log
        checkProtocVersion(cmd, protocVersion.value, log)

        val base = baseDirectory.value
        val sources = base / "src"
        val targets = target.value
        val cache = targets / "protoc" / "cache"

        (sourceDirs zip targetDirs) map { case (src, dst) =>
          val relative = src.relativeTo(sources).getOrElse(throw new Exception(s"path $src is not a in source tree $sources")).toString
          val tmp = targets / "protoc" / relative
          IO.delete(tmp)
          generate(cmd, src, tmp, log)
          transformDirectory(tmp, dst, _ => true, transformFile(_.replace("com.google.protobuf", "akka.protobuf")), cache, log)
        }
      }
    }
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
}
