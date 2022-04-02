/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.Keys._
import sbt._

object TestExtras {
  import JdkOptions.isJdk8
  object Filter {
    object Keys {
      val excludeTestNames = settingKey[Set[String]](
        "Names of tests to be excluded. Not supported by MultiJVM tests. Example usage: -Dakka.test.names.exclude=TimingSpec")
      val excludeTestTags = settingKey[Set[String]](
        "Tags of tests to be excluded. It will not be used if you specify -Dakka.test.tags.only. Example usage: -Dakka.test.tags.exclude=long-running")
      val onlyTestTags =
        settingKey[Set[String]]("Tags of tests to be ran. Example usage: -Dakka.test.tags.only=long-running")

      val checkTestsHaveRun = taskKey[Unit]("Verify a number of notable tests have actually run");
    }

    import Keys._

    private[Filter] object Params {
      val testNamesExclude = systemPropertyAsSeq("akka.test.names.exclude").toSet
      val testTagsExlcude = systemPropertyAsSeq("akka.test.tags.exclude").toSet
      val testTagsOnly = systemPropertyAsSeq("akka.test.tags.only").toSet
    }

    def settings = {
      Seq(
        excludeTestNames := Params.testNamesExclude,
        excludeTestTags := {
          if (onlyTestTags.value.isEmpty) Params.testTagsExlcude
          else Set.empty
        },
        onlyTestTags := Params.testTagsOnly,
        // add filters for tests excluded by name
        Test / testOptions ++= excludeTestNames.value.toSeq.map(exclude =>
            Tests.Filter(test => !test.contains(exclude))),
        // add arguments for tests excluded by tag
        Test / testOptions ++= {
          val tags = excludeTestTags.value
          if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-l", tags.mkString(" ")))
        },
        // add arguments for running only tests by tag
        Test / testOptions ++= {
          val tags = onlyTestTags.value
          if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-n", tags.mkString(" ")))
        },
        checkTestsHaveRun := {
          def shouldExist(description: String, filename: String): Unit =
            require(file(filename).exists, s"$description should be run as part of the build")

          val baseList =
            List(
              "The java JavaExtension.java" -> "akka-actor-tests/target/test-reports/TEST-akka.actor.JavaExtension.xml")
          val jdk9Only = List(
            "The jdk9-only FlowPublisherSinkSpec.scala" -> "akka-stream-tests/target/test-reports/TEST-akka.stream.scaladsl.FlowPublisherSinkSpec.xml",
            "The jdk9-only JavaFlowSupportCompileTest.java" -> "akka-stream-tests/target/test-reports/TEST-akka.stream.javadsl.JavaFlowSupportCompileTest.xml")

          val testsToCheck =
            if (isJdk8) baseList
            else baseList ::: jdk9Only

          testsToCheck.foreach((shouldExist _).tupled)
        })
    }

    def containsOrNotExcludesTag(tag: String) = {
      Params.testTagsOnly.contains(tag) || !Params.testTagsExlcude(tag)
    }

    def systemPropertyAsSeq(name: String): Seq[String] = {
      val prop = sys.props.get(name).getOrElse("")
      if (prop.isEmpty) Seq.empty else prop.split(",").toSeq
    }
  }

}
