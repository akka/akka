package akka.sample.osgi.test

import org.ops4j.pax.exam.CoreOptions._
import org.ops4j.pax.exam.options.DefaultCompositeOption
import org.ops4j.pax.exam.{Option => PaxOption}
import org.apache.karaf.tooling.exam.options.LogLevelOption
import org.apache.karaf.tooling.exam.options.KarafDistributionOption._
import java.io.File

/**
 * Re-usable PAX Exam option groups.
 */
object TestOptions {

  val scalaDepVersion = System.getProperty("scala.dep.version")

  def karafOptions(useDeployFolder: Boolean = false, extractInTargetFolder: Boolean = true): PaxOption = {
    val kdc = karafDistributionConfiguration.frameworkUrl(
      maven.groupId("org.apache.karaf").artifactId("apache-karaf").`type`("zip").version(System.getProperty("karaf.version")))
      .karafVersion(System.getProperty("karaf.version")).name("Apache Karaf").useDeployFolder(useDeployFolder)

    new DefaultCompositeOption(if (extractInTargetFolder) kdc.unpackDirectory(new File("target/paxexam/unpack/")) else kdc,
      editConfigurationFilePut("etc/config.properties", "karaf.framework", "equinox")
    )
  }

  def testBundles(): PaxOption = {
    new DefaultCompositeOption(
      mavenBundle("com.typesafe.akka", "akka-testkit_%s".format(scalaDepVersion)).versionAsInProject,
      mavenBundle("org.scalatest", "scalatest_%s".format(scalaDepVersion)).versionAsInProject,
      // This is needed for the scalatest osgi bundle, it has a non-optional import on a scala-actors package
      mavenBundle("org.scala-lang", "scala-actors").versionAsInProject,
      junitBundles
    )
  }

  def debugOptions(level: LogLevelOption.LogLevel = LogLevelOption.LogLevel.INFO, debugPort: Int= 5005): PaxOption = {
    new DefaultCompositeOption(
      logLevel(level),
      debugConfiguration(String.valueOf(debugPort), true),
      configureConsole().startLocalConsole(),
      configureConsole().startRemoteShell()
    )
  }

  def karafOptionsWithTestBundles(useDeployFolder: Boolean = false, extractInTargetFolder: Boolean = true): PaxOption = {
    new DefaultCompositeOption(
      karafOptions(useDeployFolder, extractInTargetFolder),
      testBundles()
    )
  }

  def featureDiningHakkers(): PaxOption = {
    akkaFeature("dining-hakker")
  }

  def akkaFeature(feature: String): PaxOption = {
    scanFeatures(maven.groupId("com.typesafe.akka.akka-sample-osgi-dining-hakkers")
      .artifactId("akka-sample-osgi-dining-hakkers") .`type`("xml").classifier("features")
      .version(System.getProperty("project.version")), feature)
  }

}
