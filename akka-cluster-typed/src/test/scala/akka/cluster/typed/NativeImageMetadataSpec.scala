/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.cluster.typed.internal.receptionist.ClusterReceptionist
import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.ModuleField
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-cluster-typed")

  val additionalEntries = Seq(
    // trixery around auto-selecting local or cluster receptionist impl
    ReflectConfigEntry(classOf[ClusterReceptionist.type].getName, fields = Seq(ModuleField)))

  val modulePackages = Seq("akka.cluster.typed", "akka.cluster.ddata.typed")

  // run this to regenerate metadata 'akka-cluster-typed/Test/runMain akka.cluster.typed.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-cluster-typed" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
