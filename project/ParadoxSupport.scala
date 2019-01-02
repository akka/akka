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
        { context: Writer.Context ⇒
                    new SignatureDirective(context.location.tree.label, context.properties, msg ⇒ log.warn(msg))
        },
        { _: Writer.Context ⇒ new UnidocDirective(allClasses) }
      )}
    }.value
  )

  class UnidocDirective(allClasses: IndexedSeq[String]) extends InlineDirective("unidoc") {
    def render(node: DirectiveNode, visitor: Visitor, printer: Printer): Unit = {
      if (node.label.split('[')(0).contains('.')) {
        val fqcn = node.label
        if (allClasses.contains(fqcn)) {
          val label = fqcn.split('.').last
          syntheticNode("java", javaLabel(label), fqcn, node).accept(visitor)
          syntheticNode("scala", label, fqcn, node).accept(visitor)
        } else {
          throw new java.lang.IllegalStateException(s"fqcn not found by @unidoc[$fqcn]")
        }
      }
      else {
        renderByClassName(node.label, node, visitor, printer)
      }
    }

    def javaLabel(label: String): String =
      label.replaceAll("\\[", "&lt;").replaceAll("\\]", "&gt;").replace('_', '?')

    def syntheticNode(group: String, label: String, fqcn: String, node: DirectiveNode): DirectiveNode = {
      val syntheticSource = new DirectiveNode.Source.Direct(fqcn)
      val attributes = new org.pegdown.ast.DirectiveAttributes.AttributeMap()
      new DirectiveNode(DirectiveNode.Format.Inline, group, null, null, attributes, null,
        new DirectiveNode(DirectiveNode.Format.Inline, group + "doc", label, syntheticSource, node.attributes, fqcn,
          new TextNode(label)
        ))
    }

    def renderByClassName(label: String, node: DirectiveNode, visitor: Visitor, printer: Printer): Unit = {
      val label = node.label.replaceAll("\\\\_", "_")
      val labelWithoutGenericParameters = label.split("\\[")(0)
      val labelWithJavaGenerics = javaLabel(label)
      val matches = allClasses.filter(_.endsWith('.' + labelWithoutGenericParameters))
      matches.size match {
        case 0 =>
          throw new java.lang.IllegalStateException(s"No matches found for $label")
        case 1 if matches(0).contains("adsl") =>
          throw new java.lang.IllegalStateException(s"Match for $label only found in one language: ${matches(0)}")
        case 1 =>
          syntheticNode("scala", label, matches(0), node).accept(visitor)
          syntheticNode("java", labelWithJavaGenerics, matches(0), node).accept(visitor)
        case 2 if matches.forall(_.contains("adsl")) =>
          matches.foreach(m => {
            if (!m.contains("javadsl"))
              syntheticNode("scala", label, m, node).accept(visitor)
            if (!m.contains("scaladsl"))
              syntheticNode("java", labelWithJavaGenerics, m, node).accept(visitor)
          })
        case n =>
          throw new java.lang.IllegalStateException(
            s"$n matches found for @unidoc[$label], but not javadsl/scaladsl: ${matches.mkString(", ")}. " +
              s"You may want to use the fully qualified class name as @unidoc[fqcn] instead of @unidoc[${label}]."
          )
      }
    }
  }

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
        //println(s"Looking for signature regex '$Signature'")
        val text =
          Source.fromFile(file)(Codec.UTF8).getLines.collect {
            case line@Signature(signature, kind, l, definition) if labels contains l.replaceAll("Mat$", "").toLowerCase() =>
              //println(s"Found label '$l' with sig '$full' in line $line")
              if (kind == "type") signature + definition
              else signature
          }.mkString("\n")

        if (text.trim.isEmpty) {
          logWarn(
            s"Did not find any signatures with one of those names [${labels.mkString(", ")}] in ${node.source} " +
            s"(was referenced from [${page.path}])")

          new HtmlBlockNode(s"""<div style="color: red;">[Broken signature inclusion [${labels.mkString(", ")}] to [${node.source}]</div>""").accept(visitor)
        } else {
          val lang = Option(node.attributes.value("type")).getOrElse(Snippet.language(file))
          new VerbatimNode(text, lang).accept(visitor)
        }
      } catch {
        case e: FileNotFoundException =>
          throw new SnipDirective.LinkException(s"Unknown snippet [${e.getMessage}] referenced from [${page.path}]")
      }
  }

}
