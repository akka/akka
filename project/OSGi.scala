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
    packagedArtifact in (Compile, packageBin) <<= (artifact in (Compile, packageBin), OsgiKeys.bundle).identityMap,
    // This will fail the build instead of accidentally removing classes from the resulting artifact.
    // Each package contained in a project MUST be known to be private or exported, if it's undecided we MUST resolve this
    OsgiKeys.failOnUndecidedPackage := true,
    // By default an entry is generated from module group-id, but our modules do not adhere to such package naming
    OsgiKeys.privatePackage := Seq()
  )

  val actor = osgiSettings ++ Seq(
    OsgiKeys.exportPackage := Seq("akka*"),
    OsgiKeys.privatePackage := Seq("akka.osgi.impl"),
    //akka-actor packages are not imported, as contained in the CP
    OsgiKeys.importPackage := (osgiOptionalImports map optionalResolution) ++ Seq("!sun.misc", scalaJava8CompatImport(), scalaVersion(scalaImport).value, configImport(), "*"),
    // dynamicImportPackage needed for loading classes defined in configuration
    OsgiKeys.dynamicImportPackage := Seq("*")
  )

  val agent = exports(Seq("akka.agent.*"))

  val camel = exports(Seq("akka.camel.*"))

  val cluster = exports(Seq("akka.cluster.*"))
  
  val clusterTools = exports(Seq("akka.cluster.singleton.*", "akka.cluster.client.*", "akka.cluster.pubsub.*"))
      
  val clusterSharding = exports(Seq("akka.cluster.sharding.*"))    

  val clusterMetrics = exports(Seq("akka.cluster.metrics.*"), imports = Seq(kamonImport(), sigarImport()))
  
  val distributedData = exports(Seq("akka.cluster.ddata.*"))

  val contrib = exports(Seq("akka.contrib.*"))

  val osgi = exports(Seq("akka.osgi.*"))

  val protobuf = exports(Seq("akka.protobuf.*"))

  val remote = exports(Seq("akka.remote.*"))

  val parsing = exports(Seq("akka.parboiled2.*", "akka.shapeless.*"),
    imports = Seq(optionalResolution("scala.quasiquotes")))

  val httpCore = exports(Seq("akka.http.*"), imports = Seq(scalaJava8CompatImport()))

  val http = exports(Seq("akka.http.impl.server") ++ 
    Seq(
      "akka.http.$DSL$.server.*",
      "akka.http.$DSL$.client.*",
      "akka.http.$DSL$.coding.*",
      "akka.http.$DSL$.common.*",
      "akka.http.$DSL$.marshalling.*",
      "akka.http.$DSL$.unmarshalling.*"
    ) flatMap { p =>
      Seq(p.replace("$DSL$", "scaladsl"), p.replace("$DSL$", "javadsl"))  
    },
    imports = Seq(
      scalaJava8CompatImport(),
      akkaImport("akka.stream.*"),
      akkaImport("akka.parboiled2.*"))
  )

  val httpTestkit = exports(Seq("akka.http.scaladsl.testkit.*", "akka.http.javadsl.testkit.*"))

  val httpSprayJson = exports(Seq("akka.http.scaladsl.marshallers.sprayjson"))

  val httpXml = exports(Seq("akka.http.scaladsl.marshallers.xml"))

  val httpJackson = exports(Seq("akka.http.javadsl.marshallers.jackson"))

  val stream =
    exports(
      packages = Seq("akka.stream.*",
                     "com.typesafe.sslconfig.akka.*"),
      imports = Seq(scalaJava8CompatImport(), scalaParsingCombinatorImport())) ++
      Seq(OsgiKeys.requireBundle := Seq(s"""com.typesafe.sslconfig;bundle-version="${Dependencies.sslConfigVersion}""""))

  val streamTestkit = exports(Seq("akka.stream.testkit.*"))

  val slf4j = exports(Seq("akka.event.slf4j.*"))

  val persistence = exports(Seq("akka.persistence.*"),
    imports = Seq(optionalResolution("org.fusesource.leveldbjni.*"), optionalResolution("org.iq80.leveldb.*")))

  val persistenceQuery = exports(Seq("akka.persistence.query.*"))

  val testkit = exports(Seq("akka.testkit.*"))

  val osgiOptionalImports = Seq(
    // needed because testkit is normally not used in the application bundle,
    // but it should still be included as transitive dependency and used by BundleDelegatingClassLoader
    // to be able to find reference.conf
    "akka.testkit")

  def exports(packages: Seq[String] = Seq(), imports: Seq[String] = Nil) = osgiSettings ++ Seq(
    OsgiKeys.importPackage := imports ++ scalaVersion(defaultImports).value,
    OsgiKeys.exportPackage := packages
  )
  def defaultImports(scalaVersion: String) = Seq("!sun.misc", akkaImport(), configImport(), "!scala.compat.java8.*",
    "!scala.util.parsing.*", scalaImport(scalaVersion), "*")
  def akkaImport(packageName: String = "akka.*") = versionedImport(packageName, "2.4", "2.5")
  def configImport(packageName: String = "com.typesafe.config.*") = versionedImport(packageName, "1.3.0", "1.4.0")
  def scalaImport(version: String) = {
    val packageName = "scala.*"
    val ScalaVersion = """(\d+)\.(\d+)\..*""".r
    val ScalaVersion(epoch, major) = version
    versionedImport(packageName, s"$epoch.$major", s"$epoch.${major.toInt+1}")
  }
  def scalaJava8CompatImport(packageName: String = "scala.compat.java8.*") = versionedImport(packageName, "0.7.0", "1.0.0")
  def scalaParsingCombinatorImport(packageName: String = "scala.util.parsing.combinator.*") = versionedImport(packageName, "1.0.4", "1.1.0")
  def kamonImport(packageName: String = "kamon.sigar.*") = optionalResolution(versionedImport(packageName, "1.6.5", "1.6.6"))
  def sigarImport(packageName: String = "org.hyperic.*") = optionalResolution(versionedImport(packageName, "1.6.5", "1.6.6"))
  def optionalResolution(packageName: String) = "%s;resolution:=optional".format(packageName)
  def versionedImport(packageName: String, lower: String, upper: String) = s"""$packageName;version="[$lower,$upper)""""
}
