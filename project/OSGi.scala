/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import com.typesafe.sbt.osgi.OsgiKeys
import com.typesafe.sbt.osgi.SbtOsgi._
import com.typesafe.sbt.osgi.SbtOsgi.autoImport._
import sbt._
import sbt.Keys._

object OSGi {

  // The included osgiSettings that creates bundles also publish the jar files
  // in the .../bundles directory which makes testing locally published artifacts
  // a pain. Create bundles but publish them to the normal .../jars directory.
  def osgiSettings = defaultOsgiSettings ++ Seq(
    packagedArtifact in (Compile, packageBin) := ((artifact in (Compile, packageBin)).value, OsgiKeys.bundle.value),
    // This will fail the build instead of accidentally removing classes from the resulting artifact.
    // Each package contained in a project MUST be known to be private or exported, if it's undecided we MUST resolve this
    OsgiKeys.failOnUndecidedPackage := true,
    // By default an entry is generated from module group-id, but our modules do not adhere to such package naming
    OsgiKeys.privatePackage := Seq(),
    // Modify constructed version to come into alignment with a valid OSGI Bundle-Version
    OsgiKeys.bundleVersion := version.value.replaceFirst("\\+", ".").replaceAll("\\+", "-")
  )

  // Need to specify scala.reflect.macros here because the bnd macro will not import this with the proper version
  val parsing = osgiPkgHeaders(
    exports = Seq("akka.parboiled2.*", "akka.shapeless.*", "akka.macros.*"),
    imports = Seq("scala.reflect.macros", optionalResolution("scala.quasiquotes")))

  val httpCore = osgiPkgHeaders(
    exports = Seq("akka.http.*"),
    imports = Seq(scalaJava8CompatImport, akkaParboiledImport, akkaShapelessImport, akkaMacrosImport))

  val http = osgiPkgHeaders(
    exports = Seq(
      "akka.http.scaladsl.client.*",
      "akka.http.#DSL#.server.*",
      "akka.http.#DSL#.coding.*",
      "akka.http.#DSL#.common.*",
      "akka.http.#DSL#.marshalling.*",
      "akka.http.#DSL#.unmarshalling.*"
    ) flatMap { p =>
      Seq(p.replace("#DSL#", "scaladsl"), p.replace("#DSL#", "javadsl"))
    },
    imports = Seq(scalaJava8CompatImport, akkaParboiledImport)
  )

  val httpTestkit = osgiPkgHeaders(
    exports = Seq("akka.http.scaladsl.testkit.*", "akka.http.javadsl.testkit.*"),
    imports = Seq("akka.testkit.*", "akka.stream.testkit.*", "org.junit") map { pkg => optionalResolution(minorVersioned(pkg)) }
  )

  val httpSprayJson = osgiPkgHeaders(exports = Seq("akka.http.scaladsl.marshallers.sprayjson"))

  val httpXml = osgiPkgHeaders(exports = Seq("akka.http.scaladsl.marshallers.xml"))

  val httpJackson = osgiPkgHeaders(exports = Seq("akka.http.javadsl.marshallers.jackson"))

  val http2Support = osgiSettings ++ Seq(
    OsgiKeys.importPackage := Seq(projectVersioned("akka.http.*"), akkaParboiledImport, akkaMacrosImport, akkaShapelessImport) ++ defaultImports,
    OsgiKeys.fragmentHost := Some("com.typesafe.akka.http.core"),
    OsgiKeys.exportPackage := Seq("""akka.http.scaladsl.*;-split-package:=merge-first"""),
    OsgiKeys.privatePackage := Seq("akka.http.impl.*")
  )

  private def osgiPkgHeaders(exports: Seq[String] = Seq(), imports: Seq[String] = Nil) = osgiSettings ++ Seq(
    OsgiKeys.importPackage := Seq(projectVersioned("akka.http.*")) ++ imports ++ defaultImports,
    OsgiKeys.exportPackage := exports
  )
  private lazy val defaultImports = Seq("!sun.misc", akkaImport, configImport, scalaImport, "*")

  private lazy val akkaParboiledImport = projectVersioned("akka.parboiled2.*")
  private lazy val akkaShapelessImport = projectVersioned("akka.shapeless.*")
  private lazy val akkaMacrosImport = projectVersioned("akka.macros.*")
  private lazy val akkaImport = "akka," + patchVersioned("akka.*")
  private lazy val configImport = patchVersioned("com.typesafe.config.*")
  private lazy val scalaImport = minorVersioned("scala.*")
  private lazy val scalaJava8CompatImport = """scala.compat.java8.*;version="$<range;[==,1)>""""

  private def projectVersioned(pkg: String) = pkg + """;version="$<range;[===,=+);$<Bundle-Version>>""""
  private def patchVersioned(pkg: String) = pkg + """;version="$<range;[===,=+)>""""
  private def minorVersioned(pkg: String) = pkg + """;version="$<range;[==,=+)>""""
  private def optionalResolution(packageName: String) = "%s;resolution:=optional".format(packageName)
}
