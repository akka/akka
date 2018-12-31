/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ Matchers, WordSpec }

import scala.collection.{ immutable ⇒ im }

class JoinConfigCompatPreDefinedChecksSpec extends WordSpec with Matchers {

  // Test for some of the pre-build helpers we offer
  "JoinConfigCompatChecker.exists" must {

    val requiredKeys = im.Seq(
      "akka.cluster.min-nr-of-members",
      "akka.cluster.retry-unsuccessful-join-after",
      "akka.cluster.allow-weakly-up-members"
    )

    "pass when all required keys are provided" in {

      val result =
        JoinConfigCompatChecker.exists(
          requiredKeys,
          config(
            """
              |{
              | akka.cluster.min-nr-of-members = 1
              | akka.cluster.retry-unsuccessful-join-after = 10s
              | akka.cluster.allow-weakly-up-members = on
              |}
            """.stripMargin)
        )

      result shouldBe Valid
    }

    "fail when some required keys are NOT provided" in {

      val Invalid(incompatibleKeys) =
        JoinConfigCompatChecker.exists(
          requiredKeys,
          config(
            """
              |{
              | akka.cluster.min-nr-of-members = 1
              |}
            """.stripMargin)
        )

      incompatibleKeys should have size 2
      incompatibleKeys should contain("akka.cluster.retry-unsuccessful-join-after is missing")
      incompatibleKeys should contain("akka.cluster.allow-weakly-up-members is missing")
    }
  }

  "JoinConfigCompatChecker.fullMatch" must {

    val requiredKeys = im.Seq(
      "akka.cluster.min-nr-of-members",
      "akka.cluster.retry-unsuccessful-join-after",
      "akka.cluster.allow-weakly-up-members"
    )

    val clusterConfig =
      config(
        """
          |{
          | akka.cluster.min-nr-of-members = 1
          | akka.cluster.retry-unsuccessful-join-after = 10s
          | akka.cluster.allow-weakly-up-members = on
          |}
        """.stripMargin)

    "pass when all required keys are provided and all match cluster config" in {

      val result =
        JoinConfigCompatChecker.fullMatch(
          requiredKeys,
          config(
            """
              |{
              | akka.cluster.min-nr-of-members = 1
              | akka.cluster.retry-unsuccessful-join-after = 10s
              | akka.cluster.allow-weakly-up-members = on
              |}
            """.stripMargin),
          clusterConfig
        )

      result shouldBe Valid
    }

    "fail when some required keys are NOT provided" in {

      val Invalid(incompatibleKeys) =
        JoinConfigCompatChecker.fullMatch(
          requiredKeys,
          config(
            """
              |{
              | akka.cluster.min-nr-of-members = 1
              |}
            """.stripMargin),
          clusterConfig
        )

      incompatibleKeys should have size 2
      incompatibleKeys should contain("akka.cluster.retry-unsuccessful-join-after is missing")
      incompatibleKeys should contain("akka.cluster.allow-weakly-up-members is missing")
    }

    "fail when all required keys are passed, but some values don't match cluster config" in {

      val Invalid(incompatibleKeys) =
        JoinConfigCompatChecker.fullMatch(
          requiredKeys,
          config(
            """
              |{
              | akka.cluster.min-nr-of-members = 1
              | akka.cluster.retry-unsuccessful-join-after = 15s
              | akka.cluster.allow-weakly-up-members = off
              |}
            """.stripMargin),
          clusterConfig
        )

      incompatibleKeys should have size 2
      incompatibleKeys should contain("akka.cluster.retry-unsuccessful-join-after is incompatible")
      incompatibleKeys should contain("akka.cluster.allow-weakly-up-members is incompatible")
    }

    "fail when all required keys are passed, but some are missing and others don't match cluster config" in {

      val Invalid(incompatibleKeys) =
        JoinConfigCompatChecker.fullMatch(
          requiredKeys,
          config(
            """
              |{
              | akka.cluster.min-nr-of-members = 1
              | akka.cluster.allow-weakly-up-members = off
              |}
            """.stripMargin),
          clusterConfig
        )

      incompatibleKeys should have size 2
      incompatibleKeys should contain("akka.cluster.retry-unsuccessful-join-after is missing")
      incompatibleKeys should contain("akka.cluster.allow-weakly-up-members is incompatible")
    }
  }

  def config(str: String): Config = ConfigFactory.parseString(str).resolve()

}
