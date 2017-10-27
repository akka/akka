/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import com.typesafe.sbt.{SbtMultiJvm, SbtScalariform}
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt._
import sbt.Keys._

object MultiNode extends AutoPlugin {

  object CliOptions {
    val multiNode = CliOption("akka.test.multi-node", false)
    val sbtLogNoFormat = CliOption("sbt.log.noformat", false)

    def seqWithProperty(name: String) = Option(System.getProperty(name)).toSeq
    val hostsFileName = seqWithProperty("akka.test.multi-node.hostsFileName")
    val javaName = seqWithProperty("akka.test.multi-node.java")
    val targetDirName = seqWithProperty("akka.test.multi-node.targetDirName")
  }

  val multiExecuteTests = CliOptions.multiNode.ifTrue(multiNodeExecuteTests in MultiJvm).getOrElse(executeTests in MultiJvm)
  val multiTest = CliOptions.multiNode.ifTrue(multiNodeTest in MultiJvm).getOrElse(test in MultiJvm)

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

    "-Xmx256m" :: akkaProperties ::: CliOptions.sbtLogNoFormat.ifTrue("-Dakka.test.nocolor=true").toList
  }

  private val multiJvmSettings =
    SbtMultiJvm.multiJvmSettings ++
    inConfig(MultiJvm)(SbtScalariform.configScalariformSettings) ++
    Seq(
      jvmOptions in MultiJvm := defaultMultiJvmOptions,
      compileInputs in (MultiJvm, compile) := ((compileInputs in (MultiJvm, compile)) dependsOn (ScalariformKeys.format in MultiJvm)).value,
      scalacOptions in MultiJvm := (scalacOptions in Test).value,
      compile in MultiJvm := ((compile in MultiJvm) triggeredBy (compile in Test)).value
    ) ++
    CliOptions.hostsFileName.map(multiNodeHostsFileName in MultiJvm := _) ++
    CliOptions.javaName.map(multiNodeJavaName in MultiJvm := _) ++
    CliOptions.targetDirName.map(multiNodeTargetDirName in MultiJvm := _) ++
    // make sure that MultiJvm tests are executed by the default test target,
    // and combine the results from ordinary test and multi-jvm tests
    (executeTests in Test := {
      val testResults = (executeTests in Test).value
      val multiNodeResults = multiExecuteTests.value
      val overall =
        if (testResults.overall.id < multiNodeResults.overall.id)
          multiNodeResults.overall
        else
          testResults.overall
      Tests.Output(overall,
        testResults.events ++ multiNodeResults.events,
        testResults.summaries ++ multiNodeResults.summaries)
    })

  implicit class TestResultOps(val self: TestResult) extends AnyVal {
    def id: Int = self match {
      case TestResult.Passed ⇒ 0
      case TestResult.Failed ⇒ 1
      case TestResult.Error  ⇒ 2
    }
  }
}

/**
 * Additional settings for scalatest.
 */
object MultiNodeScalaTest extends AutoPlugin {

  override def requires = MultiNode

  override lazy val projectSettings = Seq(
    extraOptions in MultiJvm := {
      val src = (sourceDirectory in MultiJvm).value
      (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
    },
    scalatestOptions in MultiJvm := {
      Seq("-C", "org.scalatest.extra.QuietReporter")
    }
  )
}
