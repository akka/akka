/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.ActorSystem
import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.Constructor
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectMethod
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-cluster-sharding")

  val additionalEntries = Seq(
    // akka.management.health-checks.readiness-checks.sharding
    ReflectConfigEntry(
      "akka.cluster.sharding.ClusterShardingHealthCheck",
      methods = Seq(ReflectMethod(Constructor, parameterTypes = Seq(classOf[ActorSystem].getName)))),
    // akka.cluster.configuration-compatibility-check.checkers.akka-cluster-sharding
    ReflectConfigEntry(
      "akka.cluster.sharding.JoinConfigCompatCheckSharding",
      methods = Seq(ReflectMethod(Constructor, parameterTypes = Seq.empty))))

  val modulePackages = Seq("akka.cluster.sharding")

  // run this to regenerate metadata 'akka-cluster-sharding/Test/runMain akka.cluster.sharding.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-cluster-sharding" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
