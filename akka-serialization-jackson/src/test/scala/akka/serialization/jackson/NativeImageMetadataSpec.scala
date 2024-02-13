/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.Constructor
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectMethod
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters.CollectionHasAsScala

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-serialization-jackson")

  def additionalEntries = {
    val config = ConfigFactory.load()
    val jacksonModules = config
      .getStringList("akka.serialization.jackson.jackson-modules")
      .asScala
      .map { className =>
        ReflectConfigEntry(className, methods = Seq(ReflectMethod(Constructor)))
      }
      .toVector

    jacksonModules
  }

  val modulePackages = Seq("akka.serialization.jackson")

  // run this to regenerate metadata 'akka-serialization-jackson/Test/runMain akka.serialization.jackson.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-serialization-jackson" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
