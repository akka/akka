/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt.Keys._
import sbt._

object TestExtras {

  object JUnitFileReporting {
    val settings = Seq(
      // we can enable junit-style reports everywhere with this
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a", "-u", (target.value / "test-reports").getAbsolutePath),
      testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", (target.value / "test-reports").getAbsolutePath)
    )
  }

  object Filter {
    object Keys {
      val excludeTestNames = settingKey[Set[String]]("Names of tests to be excluded. Not supported by MultiJVM tests. Example usage: -Dakka.test.names.exclude=TimingSpec")
      val excludeTestTags = settingKey[Set[String]]("Tags of tests to be excluded. It will not be used if you specify -Dakka.test.tags.only. Example usage: -Dakka.test.tags.exclude=long-running")
      val onlyTestTags = settingKey[Set[String]]("Tags of tests to be ran. Example usage: -Dakka.test.tags.only=long-running")
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
        testOptions in Test <++= excludeTestNames map { _.toSeq.map(exclude => Tests.Filter(test => !test.contains(exclude))) },

        // add arguments for tests excluded by tag
        testOptions in Test <++= excludeTestTags map { tags =>
          if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-l", tags.mkString(" ")))
        },

        // add arguments for running only tests by tag
        testOptions in Test <++= onlyTestTags map { tags =>
          if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-n", tags.mkString(" ")))
        }
      )
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
