/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.ModuleField
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectField
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-actor-typed")

  val additionalEntries = Seq(
      ReflectConfigEntry(
        "akka.actor.typed.internal.adapter.ActorSystemAdapter$LoadTypedExtensions$",
        fields = Seq(ReflectField("MODULE$"))),
      // trixery around auto-selecting local or cluster receptionist impl
      ReflectConfigEntry(
        classOf[akka.actor.typed.internal.receptionist.LocalReceptionist.type].getName,
        fields = Seq(ModuleField))) ++ serializationBindingTypeEntries

  val modulePackages = Seq("akka.actor.typed")

  def serializationBindingTypeEntries = {
    // Note: can't load from config because we'd get entries from all the other modules on classpath
    Set(
      "akka.actor.typed.ActorRef",
      "akka.actor.typed.internal.adapter.ActorRefAdapter",
      "akka.actor.typed.internal.receptionist.DefaultServiceKey").map { typeName =>
      ReflectConfigEntry(typeName)
    }
  }

  // run this to regenerate metadata 'akka-actor-typed-tests/Test/runMain akka.actor.typed.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-actor-typed" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
