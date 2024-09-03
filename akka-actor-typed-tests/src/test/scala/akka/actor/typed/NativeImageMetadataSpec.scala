/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.testkit.internal.NativeImageUtils.ModuleField
import akka.testkit.internal.NativeImageUtils.ReflectConfigEntry
import akka.testkit.internal.NativeImageUtils.ReflectField
import akka.testkit.internal.NativeImageUtils
import akka.testkit.internal.NativeImageUtils.Constructor
import akka.testkit.internal.NativeImageUtils.ReflectMethod
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val additionalEntries = Seq(
    ReflectConfigEntry(
      "akka.actor.typed.internal.adapter.ActorSystemAdapter$LoadTypedExtensions$",
      fields = Seq(ReflectField("MODULE$"))),
    // trixery around auto-selecting local or cluster receptionist impl
    ReflectConfigEntry(
      classOf[akka.actor.typed.internal.receptionist.LocalReceptionist.type].getName,
      fields = Seq(ModuleField)),
    // Flight recording (JFR additionally enabled in akka-actor native-image.properties)
    ReflectConfigEntry(
      classOf[akka.actor.typed.internal.ActorFlightRecorder.type].getName,
      methods = Seq(ReflectMethod(Constructor))))

  val nativeImageUtils = new NativeImageUtils("akka-actor-typed", additionalEntries, Seq("akka.actor.typed"))

  // run this to regenerate metadata 'akka-actor-typed-tests/Test/runMain akka.actor.typed.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    nativeImageUtils.writeMetadata()
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-actor-typed" should {

    "be up to date" in {
      val (existing, current) =
        nativeImageUtils.verifyMetadata()
      existing should ===(current)
    }
  }

}
