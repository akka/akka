package akka

import com.typesafe.sbt.osgi.SbtOsgi._
import sbt._
import sbt.Keys._

object OSGi {

  val Seq(scalaEpoch, scalaMajor) = """(\d+)\.(\d+)\..*""".r.unapplySeq(Dependencies.Versions.scalaVersion).get.map(_.toInt)

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
    OsgiKeys.importPackage := (osgiOptionalImports map optionalResolution) ++ Seq("!sun.misc", scalaImport(), configImport(), "*"),
    // dynamicImportPackage needed for loading classes defined in configuration
    OsgiKeys.dynamicImportPackage := Seq("*")
  )

  val agent = exports(Seq("akka.agent.*"))

  val camel = exports(Seq("akka.camel.*"))

  val cluster = exports(Seq("akka.cluster.*"), imports = Seq(protobufImport()))

  val osgi = exports(Seq("akka.osgi.*"))

  val osgiDiningHakkersSampleApi = exports(Seq("akka.sample.osgi.api"))

  val osgiDiningHakkersSampleCommand = osgiSettings ++ Seq(OsgiKeys.bundleActivator := Option("akka.sample.osgi.command.Activator"), OsgiKeys.privatePackage := Seq("akka.sample.osgi.command"))

  val osgiDiningHakkersSampleCore = exports(Seq("")) ++ Seq(OsgiKeys.bundleActivator := Option("akka.sample.osgi.activation.Activator"), OsgiKeys.privatePackage := Seq("akka.sample.osgi.internal", "akka.sample.osgi.activation", "akka.sample.osgi.service"))

  val osgiDiningHakkersSampleUncommons = exports(Seq("org.uncommons.maths.random")) ++ Seq(OsgiKeys.privatePackage := Seq("org.uncommons.maths.binary", "org.uncommons.maths", "org.uncommons.maths.number"))

  val remote = exports(Seq("akka.remote.*"), imports = Seq(protobufImport()))

  val slf4j = exports(Seq("akka.event.slf4j.*"))

  val persistence = exports(Seq("akka.persistence.*"), imports = Seq(protobufImport()))

  val testkit = exports(Seq("akka.testkit.*"))

  val zeroMQ = exports(Seq("akka.zeromq.*"), imports = Seq(protobufImport()) )

  val osgiOptionalImports = Seq(
    // needed because testkit is normally not used in the application bundle,
    // but it should still be included as transitive dependency and used by BundleDelegatingClassLoader
    // to be able to find refererence.conf
    "akka.testkit",
    "com.google.protobuf")

  def exports(packages: Seq[String] = Seq(), imports: Seq[String] = Nil) = osgiSettings ++ Seq(
    OsgiKeys.importPackage := imports ++ defaultImports,
    OsgiKeys.exportPackage := packages
  )
  def defaultImports = Seq("!sun.misc", akkaImport(), configImport(), scalaImport(), "*")
  def akkaImport(packageName: String = "akka.*") = versionedImport(packageName, "2.4", "2.5")
  def configImport(packageName: String = "com.typesafe.config.*") = versionedImport(packageName, "1.2.0", "1.3.0")
  def protobufImport(packageName: String = "com.google.protobuf.*") = versionedImport(packageName, "2.5.0", "2.6.0")
  def scalaImport(packageName: String = "scala.*") = versionedImport(packageName, s"$scalaEpoch.$scalaMajor", s"$scalaEpoch.${scalaMajor+1}")
  def optionalResolution(packageName: String) = "%s;resolution:=optional".format(packageName)
  def versionedImport(packageName: String, lower: String, upper: String) = s"""$packageName;version="[$lower,$upper)""""
}
