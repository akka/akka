/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.Constructor
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectField
import akka.testkit.NativeImageUtils.ReflectMethod
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-actor")

  val additionalEntries = Seq(
    ReflectConfigEntry(
      classOf[akka.actor.ActorCell].getName,
      fields = Seq(
        ReflectField("akka$actor$dungeon$Children$$_childrenRefsDoNotCallMeDirectly"),
        ReflectField("akka$actor$dungeon$Children$$_functionRefsDoNotCallMeDirectly"),
        ReflectField("akka$actor$dungeon$Children$$_nextNameDoNotCallMeDirectly"),
        ReflectField("akka$actor$dungeon$Dispatch$$_mailboxDoNotCallMeDirectly"))),
    ReflectConfigEntry(
      classOf[akka.actor.RepointableRef].getName,
      fields = Seq(ReflectField("_cellDoNotCallMeDirectly"), ReflectField("_lookupDoNotCallMeDirectly"))),
    // FIXME replace reflective props and drop these
    ReflectConfigEntry(
      "akka.actor.LocalActorRefProvider$Guardian",
      queryAllDeclaredConstructors = true,
      methods = Seq(ReflectMethod(Constructor, Seq("akka.actor.SupervisorStrategy")))),
    ReflectConfigEntry(
      "akka.actor.LocalActorRefProvider$SystemGuardian",
      queryAllDeclaredConstructors = true,
      methods = Seq(ReflectMethod(Constructor, Seq("akka.actor.SupervisorStrategy", "akka.actor.ActorRef")))))

  val modulePackages = Seq(
    "akka.actor",
    "akka.dispatch",
    "akka.event",
    "akka.io",
    "akka.japi",
    "akka.pattern",
    "akka.routing",
    "akka.serialization",
    "akka.util")

  // run this to regenerate metadata 'akka-actor-tests/Test/runMain akka.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-actor" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
