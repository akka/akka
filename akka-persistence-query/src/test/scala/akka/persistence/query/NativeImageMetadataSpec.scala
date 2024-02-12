/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query

import akka.testkit.NativeImageUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-persistence-query")

  val additionalEntries = Seq(
    // a few queries referenced but you always reference the type in your app so graal will find it?
  )

  val modulePackages = Seq("akka.persistence.query")

  // run this to regenerate metadata 'akka-persistence-query/Test/runMain akka.persistence.query.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-persistence-query" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
