/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.testkit.AkkaSpec
import akka.util.ManifestInfo.Version

class ManifestInfoSpec extends AkkaSpec {
  "ManifestInfo" should {
    "produce a clear message" in {
      val versions = Map(
        "akka-actor" -> new Version("2.6.4"),
        "akka-persistence" -> new Version("2.5.3"),
        "akka-cluster" -> new Version("2.5.3"),
        "unrelated" -> new Version("2.5.3"))
      val allModules = List("akka-actor", "akka-persistence", "akka-cluster")
      ManifestInfo.checkSameVersion("Akka", allModules, versions) shouldBe Some(
        "You are using version 2.6.4 of Akka, but it appears you (perhaps indirectly) also depend on older versions of related artifacts. " +
        "You can solve this by adding an explicit dependency on version 2.6.4 of the [akka-persistence, akka-cluster] artifacts to your project. " +
        "See also: https://doc.akka.io/docs/akka/current/common/binary-compatibility-rules.html#mixed-versioning-is-not-allowed")
    }
  }
}
