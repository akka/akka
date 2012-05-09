/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import sbt.Keys._
import java.io.File

object Sphinx {
  val sphinxDocs = SettingKey[File]("sphinx-docs")
  val sphinxTarget = SettingKey[File]("sphinx-target")
  val sphinxPygmentsDir = SettingKey[File]("sphinx-pygments-dir")
  val sphinxTags = SettingKey[Seq[String]]("sphinx-tags")
  val sphinxPygments = TaskKey[File]("sphinx-pygments", "Sphinx: install pygments styles")
  val sphinxHtml = TaskKey[File]("sphinx-html", "Sphinx: HTML documentation.")
  val sphinxLatex = TaskKey[File]("sphinx-latex", "Sphinx: Latex documentation.")
  val sphinxPdf = TaskKey[File]("sphinx-pdf", "Sphinx: PDF documentation.")
  val sphinx = TaskKey[File]("sphinx", "Build all Sphinx documentation (HTML and PDF combined).")

  lazy val settings = Seq(
    sphinxDocs <<= baseDirectory / "akka-docs",
    sphinxTarget <<= crossTarget / "sphinx",
    sphinxPygmentsDir <<= sphinxDocs { _ / "_sphinx" / "pygments" },
    sphinxTags in sphinxHtml := Seq.empty,
    sphinxTags in sphinxLatex := Seq.empty,
    sphinxPygments <<= pygmentsTask,
    sphinxHtml <<= buildTask("html", sphinxTags in sphinxHtml),
    sphinxLatex <<= buildTask("latex", sphinxTags in sphinxLatex),
    sphinxPdf <<= pdfTask,
    sphinx <<= sphinxTask
  )

  def pygmentsTask = (sphinxPygmentsDir, sphinxTarget, streams) map {
    (pygments, baseTarget, s) => {
      val target = baseTarget / "site-packages"
      val empty = (target * "*.egg").get.isEmpty
      if (empty) {
        s.log.info("Installing Sphinx pygments styles...")
        target.mkdirs()
        val logger = newLogger(s)
        val command = Seq("easy_install", "--install-dir", target.absolutePath, pygments.absolutePath)
        val env = "PYTHONPATH" -> target.absolutePath
        s.log.debug("Command: " + command.mkString(" "))
        val exitCode = Process(command, pygments, env) ! logger
        if (exitCode != 0) sys.error("Failed to install custom Sphinx pygments styles.")
        (pygments * ("*.egg-info" | "build" | "temp")).get.foreach(IO.delete)
        s.log.info("Sphinx pygments styles installed at: " + target)
      }
      target
    }
  }

  def buildTask(builder: String, tagsKey: SettingKey[Seq[String]]) = {
    (cacheDirectory, sphinxDocs, sphinxTarget, sphinxPygments, tagsKey, streams) map {
      (cacheDir, docs, baseTarget, pygments, tags, s) => {
        val target = baseTarget / builder
        val doctrees = baseTarget / "doctrees"
        val cache = cacheDir / "sphinx" / builder
        val cached = FileFunction.cached(cache)(FilesInfo.hash, FilesInfo.exists) { (in, out) =>
          val changes = in.modified
          if (!changes.isEmpty) {
            IO.delete(target)
            s.log.info("Building Sphinx %s documentation..." format builder)
            changes.foreach(file => s.log.debug("Changed documentation source: " + file))
            val logger = newLogger(s)
            val tagOptions = tags flatMap (Seq("-t", _))
            val command = Seq("sphinx-build", "-aEN", "-b", builder, "-d", doctrees.absolutePath) ++ tagOptions ++ Seq(docs.absolutePath, target.absolutePath)
            val env = "PYTHONPATH" -> pygments.absolutePath
            s.log.debug("Command: " + command.mkString(" "))
            val exitCode = Process(command, docs, env) ! logger
            if (exitCode != 0) sys.error("Failed to build Sphinx %s documentation." format builder)
            s.log.info("Sphinx %s documentation created: %s" format (builder, target))
            target.descendentsExcept("*", "").get.toSet
          } else Set.empty
        }
        val toplevel = docs * ("*" - ".*" - "_sphinx" - "_build" - "disabled" - "target")
        val inputs = toplevel.descendentsExcept("*", "").get.toSet
        cached(inputs)
        target
      }
    }
  }

  def pdfTask = (sphinxLatex, streams) map {
    (latex, s) => {
      val empty = (latex * "*.pdf").get.isEmpty
      def failed = sys.error("Failed to build Sphinx pdf documentation.")
      if (empty) {
        s.log.info("Building Sphinx pdf documentation...")
        val logger = newLogger(s)
        val exitCode = Process(Seq("make", "all-pdf"), latex) ! logger
        if (exitCode != 0) failed
      }
      val pdf = (latex * "*.pdf").get.headOption.getOrElse(failed)
      if (empty) s.log.info("Sphinx pdf documentation created: %s" format pdf)
      pdf
    }
  }

  def newLogger(streams: TaskStreams) = {
    new ProcessLogger {
      def info(o: => String): Unit = streams.log.debug(o)
      def error(e: => String): Unit = streams.log.debug(e)
      def buffer[T](f: => T): T = f
    }
  }

  def sphinxTask = (sphinxHtml, sphinxPdf, sphinxTarget, streams) map {
    (html, pdf, baseTarget, s) => {
      val target = baseTarget / "docs"
      IO.delete(target)
      IO.copyDirectory(html, target)
      IO.copyFile(pdf, target / pdf.name)
      s.log.info("Combined Sphinx documentation: %s" format target)
      target
    }
  }
}
