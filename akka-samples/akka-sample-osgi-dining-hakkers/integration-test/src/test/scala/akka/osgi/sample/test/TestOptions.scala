package akka.sample.osgi.test

import org.ops4j.pax.exam.CoreOptions._
import org.ops4j.pax.exam.options.DefaultCompositeOption
import org.ops4j.pax.exam.{Option => PaxOption}
import org.apache.karaf.tooling.exam.options.LogLevelOption
import org.apache.karaf.tooling.exam.options.KarafDistributionOption._

/**
 * Re-usable PAX Exam option groups.
 */
object TestOptions {

  val scalaDepVersion = System.getProperty("scala.dep.version")

  def karafOptions(useDeployFolder: Boolean = false): PaxOption = {
    new DefaultCompositeOption(
      karafDistributionConfiguration.frameworkUrl(
        maven.groupId("org.apache.karaf").artifactId("apache-karaf").`type`("zip").version(System.getProperty("karaf.version")))
        .karafVersion(System.getProperty("karaf.version")).name("Apache Karaf").useDeployFolder(useDeployFolder),
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

  def karafOptionsWithTestBundles(useDeployFolder: Boolean = false): PaxOption = {
    new DefaultCompositeOption(
      karafOptions(useDeployFolder),
      testBundles()
    )
  }

  def featureDiningHakkers(): PaxOption = {
    akkaFeature("dining-hakker")
  }

  def akkaFeature(feature: String): PaxOption = {
    scanFeatures(maven.groupId("com.typesafe.akka.akka-sample.dining-hakkers").artifactId("assembly-features").`type`("xml").classifier("features")
      .version(System.getProperty("project.version")), feature)
  }

}
