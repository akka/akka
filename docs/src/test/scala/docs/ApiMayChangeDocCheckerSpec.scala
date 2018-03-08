/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs

import java.lang.reflect.Method

import akka.annotation.ApiMayChange
import org.reflections.Reflections
import org.reflections.scanners.{ MethodAnnotationsScanner, TypeAnnotationsScanner }
import org.reflections.util.{ ClasspathHelper, ConfigurationBuilder }
import org.scalatest.{ Matchers, WordSpec, Assertion }

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source

class ApiMayChangeDocCheckerSpec extends WordSpec with Matchers {

  def prettifyName(clazz: Class[_]): String = {
    clazz.getCanonicalName.replaceAll("\\$minus", "-").split("\\$")(0)
  }

  // As Specs, Directives and HttpApp inherit get all directives methods, we skip those as they are not really bringing any extra info
  def removeClassesToIgnore(method: Method): Boolean = {
    Seq("Spec", ".Directives", ".HttpApp").exists(method.getDeclaringClass.getCanonicalName.contains)
  }

  def collectMissing(docPage: Seq[String])(set: Set[String], name: String): Set[String] = {
    if (docPage.exists(line => line.contains(name)))
      set
    else
      set + name
  }

  def checkNoMissingCases(missing: Set[String], typeOfUsage: String): Assertion = {
    if (missing.isEmpty) {
      succeed
    } else {
      fail(s"Please add the following missing $typeOfUsage annotated with @ApiMayChange to docs/src/main/paradox/compatibility-guidelines.md:\n${missing.map(miss => s"* $miss").mkString("\n")}")
    }
  }

  "compatibility-guidelines.md doc page" should {
    val reflections = new Reflections(new ConfigurationBuilder()
      .setUrls(ClasspathHelper.forPackage("akka.http"))
      .setScanners(
        new TypeAnnotationsScanner(),
        new MethodAnnotationsScanner()))
    val source = Source.fromFile("docs/src/main/paradox/compatibility-guidelines.md")
    try {
      val docPage = source.getLines().toList
      "contain all ApiMayChange references in classes" in {
        val classes: mutable.Set[Class[_]] = reflections.getTypesAnnotatedWith(classOf[ApiMayChange], true).asScala
        val missing = classes
          .map(prettifyName)
          .foldLeft(Set.empty[String])(collectMissing(docPage))
        checkNoMissingCases(missing, "Types")
      }
      "contain all ApiMayChange references in methods" in {
        val methods = reflections.getMethodsAnnotatedWith(classOf[ApiMayChange]).asScala
        val missing = methods
          .filterNot(removeClassesToIgnore)
          .map(method => prettifyName(method.getDeclaringClass) + "#" + method.getName)
          .foldLeft(Set.empty[String])(collectMissing(docPage))
        checkNoMissingCases(missing, "Methods")
      }
    } finally source.close()

  }
}
