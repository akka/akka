/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import akka.TestExtras.Filter
import akka.TestExtras.Filter.Keys._
import com.typesafe.sbt.{SbtScalariform, SbtMultiJvm}
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt._
import sbt.Keys._

object MultiNode extends AutoPlugin {

  object CliOption {
    val multiNode = sys.props.getOrElse("akka.test.multi-node", "false") == "true"
    val sbtLogNoFormat = sys.props.getOrElse("sbt.log.noformat", "false") == "true"

    val hostsFileName = sys.props.get("akka.test.multi-node.hostsFileName").toSeq
    val javaName = sys.props.get("akka.test.multi-node.java").toSeq
    val targetDirName = sys.props.get("akka.test.multi-node.targetDirName").toSeq
  }

  val multiTests = CliOption.multiNode(multiNodeExecuteTests in MultiJvm).getOrElse(executeTests in MultiJvm)

  override def trigger = noTrigger
  override def requires = plugins.JvmPlugin

  override lazy val projectSettings = multiJvmSettings

  private val defaultMultiJvmOptions: Seq[String] = {
    import scala.collection.JavaConverters._
    // multinode.D= and multinode.X= makes it possible to pass arbitrary
    // -D or -X arguments to the forked jvm, e.g.
    // -Dmultinode.Djava.net.preferIPv4Stack=true -Dmultinode.Xmx512m -Dmultinode.XX:MaxPermSize=256M
    // -DMultiJvm.akka.cluster.Stress.nrOfNodes=15
    val MultinodeJvmArgs = "multinode\\.(D|X)(.*)".r
    val knownPrefix = Set("multnode.", "akka.", "MultiJvm.")
    val akkaProperties = System.getProperties.propertyNames.asScala.toList.collect {
      case MultinodeJvmArgs(a, b) =>
        val value = System.getProperty("multinode." + a + b)
        "-" + a + b + (if (value == "") "" else "=" + value)
      case key: String if knownPrefix.exists(pre => key.startsWith(pre)) => "-D" + key + "=" + System.getProperty(key)
    }

    "-Xmx256m" :: akkaProperties ::: CliOption.sbtLogNoFormat("-Dakka.test.nocolor=true").toList
  }

  private val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ inConfig(MultiJvm)(SbtScalariform.configScalariformSettings) ++ Seq(
    jvmOptions in MultiJvm := defaultMultiJvmOptions,
    compileInputs in(MultiJvm, compile) <<= (compileInputs in(MultiJvm, compile)) dependsOn (ScalariformKeys.format in MultiJvm),
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test)) ++
    CliOption.hostsFileName.map(multiNodeHostsFileName in MultiJvm := _) ++
    CliOption.javaName.map(multiNodeJavaName in MultiJvm := _) ++
    CliOption.targetDirName.map(multiNodeTargetDirName in MultiJvm := _) ++
    // make sure that MultiJvm tests are executed by the default test target,
    // and combine the results from ordinary test and multi-jvm tests
    (executeTests in Test <<= (executeTests in Test, multiTests) map {
      case (testResults, multiNodeResults)  =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
    })
}

/**
 * This autoplugin adds Multi Jvm tests to validatePullRequest task.
 * It is needed, because ValidatePullRequest autoplugin does not depend on MultiNode and
 * therefore test:executeTests is not yet modified to include multi-jvm tests when ValidatePullRequest
 * build strategy is being determined.
 *
 * Making ValidatePullRequest depend on MultiNode is impossible, as then ValidatePullRequest
 * autoplugin would trigger only on projects which have both of these plugins enabled.
 */
object MultiNodeWithPrValidation extends AutoPlugin {
  import ValidatePullRequest._

  override def trigger = allRequirements
  override def requires = ValidatePullRequest && MultiNode
  override lazy val projectSettings = Seq(
    whenValidatingPr(MultiNode.multiTests)
  )
}

/**
 * Additional settings for scalatest.
 */
object MultiNodeScalaTest extends AutoPlugin {

  override def requires = MultiNode

  override lazy val projectSettings = Seq(
    extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
      (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
    },
    scalatestOptions in MultiJvm := {
      Seq("-C", "org.scalatest.extra.QuietReporter") ++
        (if (excludeTestTags.value.isEmpty) Seq.empty else Seq("-l", if (MultiNode.CliOption.multiNode) excludeTestTags.value.mkString("\"", " ", "\"") else excludeTestTags.value.mkString(" "))) ++
        (if (onlyTestTags.value.isEmpty) Seq.empty else Seq("-n", if (MultiNode.CliOption.multiNode) onlyTestTags.value.mkString("\"", " ", "\"") else onlyTestTags.value.mkString(" ")))
    }
  )
}
