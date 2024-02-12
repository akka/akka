/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.metrics

import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.Constructor
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectMethod
import com.typesafe.config.Config
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-cluster-metrics")

  val additionalEntries = Seq(
    // akka.cluster.metrics.supervisor.strategy.provider
    ReflectConfigEntry(
      "akka.cluster.metrics.ClusterMetricsStrategy",
      methods = Seq(ReflectMethod(Constructor, parameterTypes = Seq(classOf[Config].getName)))))

  val modulePackages = Seq("akka.cluster.metrics")

  // run this to regenerate metadata 'akka-cluster-metrics/Test/runMain akka.cluster.metrics.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-cluster-metrics" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
