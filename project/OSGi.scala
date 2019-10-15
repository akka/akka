/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import com.typesafe.sbt.osgi.OsgiKeys
import com.typesafe.sbt.osgi.SbtOsgi._
import com.typesafe.sbt.osgi.SbtOsgi.autoImport._
import sbt._
import sbt.Keys._
import net.bzzt.reproduciblebuilds.ReproducibleBuildsPlugin

object OSGi {

  // The included osgiSettings that creates bundles also publish the jar files
  // in the .../bundles directory which makes testing locally published artifacts
  // a pain. Create bundles but publish them to the normal .../jars directory.
  def osgiSettings =
    defaultOsgiSettings ++ Seq(
      Compile / packageBin := {
        val bundle = OsgiKeys.bundle.value
        // This normally happens automatically when loading the
        // sbt-reproducible-builds plugin, but because we replace
        // `packageBin` wholesale here we need to invoke the post-processing
        // manually. See also
        // https://github.com/raboof/sbt-reproducible-builds#sbt-osgi
        ReproducibleBuildsPlugin.postProcessJar(bundle)
      },
      // This will fail the build instead of accidentally removing classes from the resulting artifact.
      // Each package contained in a project MUST be known to be private or exported, if it's undecided we MUST resolve this
      OsgiKeys.failOnUndecidedPackage := true,
      // By default an entry is generated from module group-id, but our modules do not adhere to such package naming
      OsgiKeys.privatePackage := Seq(),
      // Explicitly specify the version of JavaSE required #23795 (rather depend on
      // figuring that out from the JDK it was built with)
      OsgiKeys.requireCapability := "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version>=1.8))\"")

  val actor = osgiSettings ++ Seq(
      OsgiKeys.exportPackage := Seq("akka*"),
      OsgiKeys.privatePackage := Seq("akka.osgi.impl"),
      //akka-actor packages are not imported, as contained in the CP
      OsgiKeys.importPackage := (osgiOptionalImports.map(optionalResolution)) ++ Seq(
          "!sun.misc",
          scalaJava8CompatImport(),
          scalaVersion(scalaImport).value,
          configImport(),
          "*"),
      // dynamicImportPackage needed for loading classes defined in configuration
      OsgiKeys.dynamicImportPackage := Seq("*"))

  val actorTyped = exports(Seq("akka.actor.typed.*"))

  val cluster = exports(Seq("akka.cluster.*"))

  val clusterTools = exports(Seq("akka.cluster.singleton.*", "akka.cluster.client.*", "akka.cluster.pubsub.*"))

  val clusterSharding = exports(Seq("akka.cluster.sharding.*"))

  val clusterMetrics = exports(Seq("akka.cluster.metrics.*"), imports = Seq(kamonImport(), sigarImport()))

  val distributedData = exports(Seq("akka.cluster.ddata.*"))

  val osgi = exports(Seq("akka.osgi.*"))

  val protobuf = exports(Seq("akka.protobuf.*"))

  val jackson = exports(Seq("akka.serialization.jackson.*"))

  val remote = exports(Seq("akka.remote.*"))

  val stream =
    exports(
      packages = Seq("akka.stream.*", "com.typesafe.sslconfig.akka.*"),
      imports = Seq(
        scalaJava8CompatImport(),
        scalaParsingCombinatorImport(),
        sslConfigCoreImport("com.typesafe.sslconfig.ssl.*"),
        sslConfigCoreImport("com.typesafe.sslconfig.util.*"),
        "!com.typesafe.sslconfig.akka.*"))

  val streamTestkit = exports(Seq("akka.stream.testkit.*"))

  val slf4j = exports(Seq("akka.event.slf4j.*"))

  val persistence = exports(
    Seq("akka.persistence.*"),
    imports = Seq(optionalResolution("org.fusesource.leveldbjni.*"), optionalResolution("org.iq80.leveldb.*")))

  val persistenceTyped = exports(Seq("akka.persistence.typed.*"))

  val persistenceQuery = exports(Seq("akka.persistence.query.*"))

  val testkit = exports(Seq("akka.testkit.*"))

  val discovery = exports(Seq("akka.discovery.*"))

  val coordination = exports(Seq("akka.coordination.*"))

  val osgiOptionalImports = Seq(
    // needed because testkit is normally not used in the application bundle,
    // but it should still be included as transitive dependency and used by BundleDelegatingClassLoader
    // to be able to find reference.conf
    "akka.testkit")

  def exports(packages: Seq[String] = Seq(), imports: Seq[String] = Nil) =
    osgiSettings ++ Seq(
      OsgiKeys.importPackage := imports ++ scalaVersion(defaultImports).value,
      OsgiKeys.exportPackage := packages)
  def defaultImports(scalaVersion: String) =
    Seq(
      "!sun.misc",
      akkaImport(),
      configImport(),
      "!scala.compat.java8.*",
      "!scala.util.parsing.*",
      scalaImport(scalaVersion),
      "*")
  def akkaImport(packageName: String = "akka.*") = versionedImport(packageName, "2.6", "2.7")
  def configImport(packageName: String = "com.typesafe.config.*") = versionedImport(packageName, "1.4.0", "1.5.0")
  def scalaImport(version: String) = {
    val packageName = "scala.*"
    val ScalaVersion = """(\d+)\.(\d+)\..*""".r
    val ScalaVersion(epoch, major) = version
    versionedImport(packageName, s"$epoch.$major", s"$epoch.${major.toInt + 1}")
  }
  def scalaJava8CompatImport(packageName: String = "scala.compat.java8.*") =
    versionedImport(packageName, "0.8.0", "1.0.0")
  def scalaParsingCombinatorImport(packageName: String = "scala.util.parsing.combinator.*") =
    versionedImport(packageName, "1.1.0", "1.2.0")
  def sslConfigCoreImport(packageName: String = "com.typesafe.sslconfig") =
    versionedImport(packageName, "0.4.0", "1.0.0")
  def sslConfigCoreSslImport(packageName: String = "com.typesafe.sslconfig.ssl.*") =
    versionedImport(packageName, "0.4.0", "1.0.0")
  def sslConfigCoreUtilImport(packageName: String = "com.typesafe.sslconfig.util.*") =
    versionedImport(packageName, "0.4.0", "1.0.0")
  def kamonImport(packageName: String = "kamon.sigar.*") =
    optionalResolution(versionedImport(packageName, "1.6.5", "1.6.6"))
  def sigarImport(packageName: String = "org.hyperic.*") =
    optionalResolution(versionedImport(packageName, "1.6.5", "1.6.6"))
  def optionalResolution(packageName: String) = "%s;resolution:=optional".format(packageName)
  def versionedImport(packageName: String, lower: String, upper: String) = s"""$packageName;version="[$lower,$upper)""""
}
