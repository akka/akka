/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.Constructor
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectMethod
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-cluster-sharding-typed")

  val additionalEntries = Seq(
    // akka.cluster.configuration-compatibility-check.checkers.akka-cluster-sharding-hash-extractor
    ReflectConfigEntry(
      "akka.cluster.sharding.typed.internal.JoinConfigCompatCheckerClusterSharding",
      methods = Seq(ReflectMethod(Constructor, parameterTypes = Seq.empty))))

  val modulePackages = Seq("akka.cluster.sharding.typed")

  // run this to regenerate metadata 'akka-cluster-sharding-typed/Test/runMain akka.cluster.sharding.typed.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-cluster-sharding-typed" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
