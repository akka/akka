/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed

import akka.testkit.internal.NativeImageUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val nativeImageUtils = new NativeImageUtils("akka-stream-typed", Seq(), Seq("akka.stream.typed"))

  // run this to regenerate metadata 'akka-stream-typed/Test/runMain akka.stream.typed.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    nativeImageUtils.writeMetadata()
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-stream-typed" should {

    "be up to date" in {
      val (existing, current) = nativeImageUtils.verifyMetadata()
      existing should ===(current)
    }
  }

}
