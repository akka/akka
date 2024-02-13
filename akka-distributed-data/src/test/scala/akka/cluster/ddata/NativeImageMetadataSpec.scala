/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.testkit.internal.NativeImageUtils.Constructor
import akka.testkit.internal.NativeImageUtils.ReflectConfigEntry
import akka.testkit.internal.NativeImageUtils.ReflectMethod
import akka.testkit.internal.NativeImageUtils
import com.typesafe.config.Config
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val additionalEntries = Seq(
    // akka.cluster.distributed-data.durable.store-actor-class
    ReflectConfigEntry(
      classOf[LmdbDurableStore].getName,
      methods = Seq(ReflectMethod(Constructor, parameterTypes = Seq(classOf[Config].getName)))))

  val nativeImageUtils = new NativeImageUtils("akka-distributed-data", additionalEntries, Seq("akka.cluster.ddata"))

  // run this to regenerate metadata 'akka-distributed-data/Test/runMain akka.cluster.ddata.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    nativeImageUtils.writeMetadata()
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-distributed-data" should {

    "be up to date" in {
      val (existing, current) = nativeImageUtils.verifyMetadata()
      existing should ===(current)
    }
  }

}
