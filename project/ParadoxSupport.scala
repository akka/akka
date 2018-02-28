/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import _root_.io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import com.lightbend.paradox.markdown._
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._
import org.pegdown.Printer
import org.pegdown.ast._
import sbt.Keys._
import sbt._

import scala.collection.JavaConverters._

object ParadoxSupport {
  val paradoxWithCustomDirectives = Seq(
    paradoxDirectives ++= Def.taskDyn {
      val classpath = (fullClasspath in Compile).value.files.map(_.toURI.toURL).toArray
      val classloader = new java.net.URLClassLoader(classpath, this.getClass().getClassLoader())
      lazy val scanner = new FastClasspathScanner("akka").addClassLoader(classloader).scan()
      val allClasses = scanner.getNamesOfAllClasses.asScala.toVector
      Def.task { Seq(
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
}
