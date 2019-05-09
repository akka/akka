/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.{File, FileNotFoundException}

import com.lightbend.paradox.markdown._
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._
import org.pegdown.Printer
import org.pegdown.ast._
import sbt.Keys._
import sbt._

import scala.annotation.tailrec
import scala.io.{Codec, Source}
import scala.collection.JavaConverters._

object ParadoxSupport {
  val paradoxWithCustomDirectives = Seq(
    paradoxDirectives ++= Def.taskDyn {
      val log = streams.value.log
      val classpath = (fullClasspath in Compile).value.files.map(_.toURI.toURL).toArray
      val classloader = new java.net.URLClassLoader(classpath, this.getClass().getClassLoader())
      import _root_.io.github.classgraph.ClassGraph
      lazy val scanner = new ClassGraph()
        .whitelistPackages("akka")
        .addClassLoader(classloader)
        .scan()
      val allClasses = scanner.getAllClasses.getNames.asScala.toVector
      Def.task { Seq(
        { context: Writer.Context =>
                    new SignatureDirective(context.location.tree.label, context.properties, msg => log.warn(msg))
        },
      )}
    }.value
  )

  class SignatureDirective(page: Page, variables: Map[String, String], logWarn: String => Unit) extends LeafBlockDirective("signature") {
    def render(node: DirectiveNode, visitor: Visitor, printer: Printer): Unit =
      try {
        val labels = node.attributes.values("identifier").asScala.map(_.toLowerCase())
        val source = node.source match {
          case direct: DirectiveNode.Source.Direct => direct.value
          case _                                   => sys.error("Source references are not supported")
        }
        val file =
          if (source startsWith "/") {
            // snip.build.base_dir defined by Paradox
            val base = new File(PropertyUrl("snip.build.base_dir", variables.get).base.trim)
            new File(base, source)
          } else new File(page.file.getParentFile, source)

        val Signature = """\s*((def|val|type) (\w+)(?=[:(\[]).*)(\s+\=.*)""".r // stupid approximation to match a signature

        val text =
          getDefs(file).collect {
            case line@Signature(signature, kind, l, definition) if labels contains l.replaceAll("Mat$", "").toLowerCase() =>
              //println(s"Found label '$l' with sig '$full' in line $line")
              if (kind == "type") signature + definition
              else signature
          }.mkString("\n")

        if (text.trim.isEmpty) {
          throw new IllegalArgumentException(
            s"Did not find any signatures with one of those names [${labels.mkString(", ")}] in $source " +
            s"(was referenced from [${page.path}])")
        } else {
          val lang = Option(node.attributes.value("type")).getOrElse(Snippet.language(file))
          new VerbatimNode(text, lang).accept(visitor)
        }
      } catch {
        case e: FileNotFoundException =>
          throw new SnipDirective.LinkException(s"Unknown snippet [${e.getMessage}] referenced from [${page.path}]")
      }
  }

  def getDefs(file: File): Seq[String] = {
    val Indented = "(\\s*)(.*)".r

    @tailrec
    def rec(lines: Iterator[String], currentDef: Option[String], defIndent: Integer, soFar: Seq[String]): Seq[String] = {
      if (!lines.hasNext) soFar ++ currentDef
      else lines.next() match {
        case Indented(indent, line) =>
          if (line.startsWith("def")) rec(lines, Some(line), indent.length, soFar ++ currentDef)
          else if (indent.length == defIndent + 4) rec(lines, currentDef.map(_ ++ line), defIndent, soFar)
          else rec(lines, None, 0, soFar ++ currentDef)
      }
    }
    rec(Source.fromFile(file)(Codec.UTF8).getLines, None, 0, Nil)
  }
}
