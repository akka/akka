package akka

import sbt._
import sbt.Keys._
import java.io.File

object Rstdoc {
  val rstdocDirectory = SettingKey[File]("rstdoc-directory")
  val rstdocTarget = SettingKey[File]("rstdoc-target")
  val rstdoc = TaskKey[File]("rstdoc", "Build the reStructuredText documentation.")

  lazy val settings = Seq(
    rstdocDirectory <<= baseDirectory / "akka-docs",
    rstdocTarget <<= crossTarget / "rstdoc",
    rstdoc <<= rstdocTask
  )

  def rstdocTask = (rstdocDirectory, rstdocTarget, streams) map {
    (dir, target, s) => {
      s.log.info("Building reStructuredText documentation...")
      val logger = new ProcessLogger {
        def info(o: => String): Unit = s.log.debug(o)
        def error(e: => String): Unit = s.log.debug(e)
        def buffer[T](f: => T): T = f
      }
      val exitCode = Process(List("make", "clean", "html", "pdf"), dir) ! logger
      if (exitCode != 0) sys.error("Failed to build docs.")
      s.log.info("Creating reStructuredText documentation successful.")
      IO.copyDirectory(dir / "_build" / "html", target)
      IO.copyFile(dir / "_build" / "latex" / "Akka.pdf", target / "Akka.pdf")
      target
    }
  }
}
