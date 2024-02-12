/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.ActorSystem
import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.Constructor
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectMethod
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-cluster")

  val additionalEntries = Seq(
    // akka.cluster.configuration-compatibility-check.checkers.akka-cluster
    ReflectConfigEntry(
      "akka.cluster.JoinConfigCompatCheckCluster",
      methods = Seq(ReflectMethod(Constructor, Seq.empty))),
    // akka.cluster.downing-provider-class
    ReflectConfigEntry(
      "akka.cluster.sbr.SplitBrainResolverProvider",
      methods = Seq(ReflectMethod(Constructor, Seq(classOf[ActorSystem].getName)))))

  val modulePackages = Seq("akka.cluster")

  // run this to regenerate metadata 'akka-cluster/Test/runMain akka.cluster.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-cluster" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
