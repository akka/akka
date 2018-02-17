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
      val log = streams.value.log
      val classpath = (fullClasspath in Compile).value.files.map(_.toURI.toURL).toArray
      val classloader = new java.net.URLClassLoader(classpath, this.getClass().getClassLoader())
      lazy val scanner = new FastClasspathScanner("akka").addClassLoader(classloader).scan()
      val allClasses = scanner.getNamesOfAllClasses.asScala.toVector
      val directives = paradoxDirectives.value
      Def.task { Seq(
        { context: Writer.Context ⇒ {
            val scaladocDirective = directives.map(_.apply(context)).collectFirst { case x: ScaladocDirective => x }.get
            val javadocDirective = directives.map(_.apply(context)).collectFirst { case x: JavadocDirective => x }.get
            new UnidocDirective(scaladocDirective, javadocDirective, allClasses)
          }
        }
      )}
    }.value
  )

  class UnidocDirective(scaladocDirective: ScaladocDirective, javadocDirective: JavadocDirective, allClasses: IndexedSeq[String]) extends InlineDirective("unidoc") {
    def render(node: DirectiveNode, visitor: Visitor, printer: Printer): Unit = {
      def syntheticNode(group: String, label: String, c: String): DirectiveNode = {
        val syntheticSource = new DirectiveNode.Source.Direct(c)
        val attributes = new org.pegdown.ast.DirectiveAttributes.AttributeMap()
        new DirectiveNode(DirectiveNode.Format.Inline, group, null, null, attributes, null,
          new DirectiveNode(DirectiveNode.Format.Inline, group + "doc", label, syntheticSource, node.attributes, c,
            new TextNode(label)
          ))
      }

      val label = node.label.replaceAll("\\\\_", "_")
      val labelWithoutGenericParameters = label.split("\\[")(0)
      val labelWithJavaGenerics = label.replaceAll("\\[", "&lt;").replaceAll("\\]", "&gt;").replace('_', '?')
      val matches = allClasses.filter(_.endsWith('.' + labelWithoutGenericParameters))
      matches.size match {
        case 0 =>
          throw new java.lang.IllegalStateException(s"No matches found for $label")
        case 1 if matches(0).contains("adsl") =>
          throw new java.lang.IllegalStateException(s"Match for $label only found in one language: ${matches(0)}")
        case 1 =>
          syntheticNode("scala", label, matches(0)).accept(visitor)
          syntheticNode("java", labelWithJavaGenerics, matches(0)).accept(visitor)
        case 2 if matches.forall(_.contains("adsl")) =>
          matches.foreach(m => {
            if (!m.contains("javadsl"))
              syntheticNode("scala", label, m).accept(visitor)
            if (!m.contains("scaladsl"))
              syntheticNode("java", labelWithJavaGenerics, m).accept(visitor)
          })
        case 2 =>
          throw new java.lang.IllegalStateException(s"2 matches found for $label, but not javadsl/scaladsl: ${matches.mkString(", ")}")
        case n =>
          throw new java.lang.IllegalStateException(s"$n matches found for $label, but not javadsl/scaladsl: ${matches.mkString(", ")}")
      }
    }
  }
}
