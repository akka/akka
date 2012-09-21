/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import sbt._
import sbt.Keys._
import java.io.{ File, PrintWriter }

object Sphinx {
  val sphinxDocs = SettingKey[File]("sphinx-docs")
  val sphinxTarget = SettingKey[File]("sphinx-target")
  val sphinxPygmentsDir = SettingKey[File]("sphinx-pygments-dir")
  val sphinxTags = SettingKey[Seq[String]]("sphinx-tags")
  val sphinxPygments = TaskKey[File]("sphinx-pygments", "Sphinx: install pygments styles")
  val sphinxHtml = TaskKey[File]("sphinx-html", "Sphinx: HTML documentation.")
  val sphinxLatex = TaskKey[File]("sphinx-latex", "Sphinx: Latex documentation.")
  val sphinxPdf = TaskKey[File]("sphinx-pdf", "Sphinx: PDF documentation.")
  val sphinxVars = SettingKey[Map[String, String]]("sphinx-vars", "mappings key->value to be replaced within docs")
  val sphinxExts = SettingKey[Set[String]]("sphinx-exts", "file extensions which will be filtered for replacements")
  val sphinx = TaskKey[File]("sphinx", "Build all Sphinx documentation (HTML and PDF combined).")

  lazy val settings = Seq(
    sphinxDocs <<= baseDirectory / "rst",
    sphinxTarget <<= crossTarget / "sphinx",
    sphinxPygmentsDir <<= sphinxDocs { _ / ".." / "_sphinx" / "pygments" },
    sphinxTags in sphinxHtml := Seq.empty,
    sphinxTags in sphinxLatex := Seq.empty,
    sphinxPygments <<= pygmentsTask,
    sphinxHtml <<= buildTask("html", sphinxTags in sphinxHtml),
    sphinxLatex <<= buildTask("latex", sphinxTags in sphinxLatex),
    sphinxPdf <<= pdfTask,
    sphinxVars := Map("" -> "@"), // this default makes the @@ -> @ subst work
    sphinxExts := Set("rst"),
    sphinx <<= sphinxTask
  )

  def pygmentsTask = (sphinxDocs, sphinxPygmentsDir, sphinxTarget, streams) map {
    (cwd, pygments, baseTarget, s) => {
      val target = baseTarget / "site-packages"
      val empty = (target * "*.egg").get.isEmpty
      if (empty) {
        s.log.info("Installing Sphinx pygments styles...")
        target.mkdirs()
        val logger = newLogger(s)
        val command = Seq("easy_install", "--install-dir", target.absolutePath, pygments.absolutePath)
        val env = "PYTHONPATH" -> target.absolutePath
        s.log.debug("Command: " + command.mkString(" ") + "\nEnv:" + env)
        val exitCode = Process(command, cwd, env) ! logger
        if (exitCode != 0) sys.error("Failed to install custom Sphinx pygments styles: exit code " + exitCode)
        (pygments * ("*.egg-info" | "build" | "temp")).get.foreach(IO.delete)
        s.log.info("Sphinx pygments styles installed at: " + target)
      }
      target
    }
  }

  def buildTask(builder: String, tagsKey: SettingKey[Seq[String]]) = {
    (cacheDirectory, sphinxDocs, sphinxTarget, sphinxPygments, tagsKey, streams, sphinxVars, sphinxExts) map {
      (cacheDir, docs, baseTarget, pygments, tags, s, replacements, filterExt) => {
        val target = baseTarget / builder
        val doctrees = baseTarget / "doctrees" / builder
        val temp = docs.getParentFile / (docs.getName + "_" + builder)
        val cache = cacheDir / "sphinx" / builder
        val cached = FileFunction.cached(cache)(FilesInfo.hash, FilesInfo.exists) { (in, out) =>
          def dst(f: File) = temp.toPath.resolve(docs.toPath.relativize(f.toPath)).toFile
          def filter(f: File) = filterExt contains f.getName.reverse.takeWhile('.' !=).reverse
          val Replacer = """@(\w+)@""".r
          /*
           * First Step: bring filtered source tree in sync with orig source tree
           */
          // delete files which were removed
          in.removed foreach (f => IO delete dst(f))
          // transform the other files by applying the replacement map for @<key>@ tokens
          (in.modified ++ (in.checked -- out.checked)).toSeq.sorted foreach { f =>
            if (f.isFile)
              if (filter(f)) {
                s.log.debug("Changed documentation source: " + f)
                IO.reader(f) { reader =>
                  IO.writer(dst(f), "", IO.defaultCharset, append = false) { writer =>
                    val wr = new PrintWriter(writer)
                    IO.foreachLine(reader) { line =>
                      wr.println(Replacer.replaceAllIn(line, m => replacements.getOrElse(m.group(1), {
                          s.log.warn("unknown replacement " + m.group(1) + " in " + replacements)
                          m.group(0)
                        })))
                    }
                  }
                }
              } else {
                // do not transform PNGs et al
                s.log.debug("Changed documentation source (copying): " + f)
                IO.copyFile(f, dst(f))
              }
          }
          /*
           * Second Step: invoke sphinx-build
           */
          val tagList = if (tags.isEmpty) "" else tags.mkString(" (", ", ", ")")
          val desc = "%s%s" format (builder, tagList)
          s.log.info("Building Sphinx %s documentation..." format desc)
          val logger = newLogger(s)
          val tagOptions = tags flatMap (Seq("-t", _))
          val command = Seq("sphinx-build", "-aEN", "-b", builder, "-d", doctrees.absolutePath) ++ tagOptions ++ Seq(temp.absolutePath, target.absolutePath)
          val env = "PYTHONPATH" -> pygments.absolutePath
          s.log.debug("Command: " + command.mkString(" "))
          val exitCode = Process(command, docs, env) ! logger
          if (exitCode != 0) sys.error("Failed to build Sphinx %s documentation." format desc)
          s.log.info("Sphinx %s documentation created: %s" format (desc, target))
          temp.descendentsExcept("*", "").get.toSet
        }
        val toplevel = docs * ("*" - "disabled")
        val inputs = toplevel.descendentsExcept("*", "").get.toSet
        cached(inputs)
        target
      }
    }
  }

  def pdfTask = (sphinxLatex, streams) map {
    (latex, s) => {
      val pdf = latex / "Akka.pdf"
      def failed = sys.error("Failed to build Sphinx pdf documentation.")
      if (!pdf.exists) {
        s.log.info("Building Sphinx pdf documentation...")
        val logger = newLogger(s)
        val exitCode = Process(Seq("make", "all-pdf"), latex) ! logger
        if (exitCode != 0) failed
        s.log.info("Sphinx pdf documentation created: %s" format pdf)
      }
      pdf
    }
  }

  def newLogger(streams: TaskStreams) = {
    new ProcessLogger {
      def info(message: => String): Unit = {
        val m = message
        if (m contains "ERROR") streams.log.error(message)
        else if (m contains "WARNING") streams.log.warn(message)
        else streams.log.debug(message)
      }
      def error(e: => String): Unit = streams.log.warn(e)
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
