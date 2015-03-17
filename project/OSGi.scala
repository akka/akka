package akka

import com.typesafe.sbt.osgi.SbtOsgi._
import sbt._
import sbt.Keys._

object OSGi {

  // The included osgiSettings that creates bundles also publish the jar files
  // in the .../bundles directory which makes testing locally published artifacts
  // a pain. Create bundles but publish them to the normal .../jars directory.
  def osgiSettings = defaultOsgiSettings ++ Seq(
    packagedArtifact in (Compile, packageBin) <<= (artifact in (Compile, packageBin), OsgiKeys.bundle).identityMap
  )

  val actor = osgiSettings ++ Seq(
    OsgiKeys.exportPackage := Seq("akka*"),
    OsgiKeys.privatePackage := Seq("akka.osgi.impl"),
    //akka-actor packages are not imported, as contained in the CP
    OsgiKeys.importPackage := (osgiOptionalImports map optionalResolution) ++ Seq("!sun.misc", scalaVersion(scalaImport).value, configImport(), "*"),
    // dynamicImportPackage needed for loading classes defined in configuration
    OsgiKeys.dynamicImportPackage := Seq("*")
  )

  val agent = exports(Seq("akka.agent.*"))

  val camel = exports(Seq("akka.camel.*"))

  val cluster = exports(Seq("akka.cluster.*"), imports = Seq(protobufImport()))

  val clusterMetrics = exports(Seq("akka.cluster.metrics.*"), imports = Seq(protobufImport(),kamonImport(),sigarImport()))

  val osgi = exports(Seq("akka.osgi.*"))

  val remote = exports(Seq("akka.remote.*"), imports = Seq(protobufImport()))

  val slf4j = exports(Seq("akka.event.slf4j.*"))

  val persistence = exports(Seq("akka.persistence.*"), imports = Seq(protobufImport()))

  val testkit = exports(Seq("akka.testkit.*"))

  val osgiOptionalImports = Seq(
    // needed because testkit is normally not used in the application bundle,
    // but it should still be included as transitive dependency and used by BundleDelegatingClassLoader
    // to be able to find refererence.conf
    "akka.testkit",
    "com.google.protobuf")

  def exports(packages: Seq[String] = Seq(), imports: Seq[String] = Nil) = osgiSettings ++ Seq(
    OsgiKeys.importPackage := imports ++ scalaVersion(defaultImports).value,
    OsgiKeys.exportPackage := packages
  )
  def defaultImports(scalaVersion: String) = Seq("!sun.misc", akkaImport(), configImport(), scalaImport(scalaVersion), "*")
  def akkaImport(packageName: String = "akka.*") = versionedImport(packageName, "2.4", "2.5")
  def configImport(packageName: String = "com.typesafe.config.*") = versionedImport(packageName, "1.2.0", "1.3.0")
  def protobufImport(packageName: String = "com.google.protobuf.*") = versionedImport(packageName, "2.5.0", "2.6.0")
  def scalaImport(version: String) = {
    val packageName = "scala.*"
    val ScalaVersion = """(\d+)\.(\d+)\..*""".r
    val ScalaVersion(epoch, major) = version
    versionedImport(packageName, s"$epoch.$major", s"$epoch.${major.toInt+1}")
  }
  def kamonImport(packageName: String = "kamon.sigar.*") = optionalResolution(versionedImport(packageName, "1.6.5", "1.6.6"))
  def sigarImport(packageName: String = "org.hyperic.*") = optionalResolution(versionedImport(packageName, "1.6.5", "1.6.6"))
  def optionalResolution(packageName: String) = "%s;resolution:=optional".format(packageName)
  def versionedImport(packageName: String, lower: String, upper: String) = s"""$packageName;version="[$lower,$upper)""""
}
