/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import akka.testkit.internal.NativeImageUtils
import akka.testkit.internal.NativeImageUtils.Constructor
import akka.testkit.internal.NativeImageUtils.ReflectConfigEntry
import akka.testkit.internal.NativeImageUtils.ReflectMethod
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters.CollectionHasAsScala

object NativeImageMetadataSpec {

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

  val nativeImageUtils =
    new NativeImageUtils("akka-serialization-jackson", additionalEntries, Seq("akka.serialization.jackson"))

  // run this to regenerate metadata 'akka-serialization-jackson/Test/runMain akka.serialization.jackson.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    nativeImageUtils.writeMetadata()
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-serialization-jackson" should {

    "be up to date" in {
      val (existing, current) = nativeImageUtils.verifyMetadata()
      existing should ===(current)
    }
  }

}
