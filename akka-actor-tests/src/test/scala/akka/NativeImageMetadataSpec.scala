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
    // dungeon or dungeon worthy unsafe trixery
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
    ReflectConfigEntry(
      classOf[akka.pattern.CircuitBreaker].getName,
      fields =
        Seq(ReflectField("_currentResetTimeoutDoNotCallMeDirectly"), ReflectField("_currentStateDoNotCallMeDirectly"))),
    ReflectConfigEntry(
      classOf[akka.pattern.PromiseActorRef].getName,
      fields = Seq(ReflectField("_stateDoNotCallMeDirectly"), ReflectField("_watchedByDoNotCallMeDirectly"))),
    // loaded via config
    ReflectConfigEntry(
      "akka.actor.LocalActorRefProvider$Guardian",
      queryAllDeclaredConstructors = true,
      methods = Seq(ReflectMethod(Constructor, Seq("akka.actor.SupervisorStrategy")))),
    ReflectConfigEntry(
      "akka.actor.LocalActorRefProvider$SystemGuardian",
      queryAllDeclaredConstructors = true,
      methods = Seq(ReflectMethod(Constructor, Seq("akka.actor.SupervisorStrategy", "akka.actor.ActorRef")))),
    // logging infra
    ReflectConfigEntry(
      classOf[akka.event.Logging.DefaultLogger].getName,
      methods = Seq(ReflectMethod(NativeImageUtils.Constructor))),
    ReflectConfigEntry(
      classOf[akka.event.DefaultLoggingFilter].getName,
      methods = Seq(
        ReflectMethod(
          NativeImageUtils.Constructor,
          parameterTypes = Seq("akka.actor.ActorSystem$Settings", "akka.event.EventStream")))),
    // akka io stuff
    ReflectConfigEntry(
      classOf[akka.io.InetAddressDnsProvider].getName,
      methods = Seq(ReflectMethod(NativeImageUtils.Constructor))),
    // pluggable through deprecated InetAddressDnsProvider
    ReflectConfigEntry(
      classOf[akka.io.InetAddressDnsResolver].getName,
      methods = Seq(
        ReflectMethod(
          NativeImageUtils.Constructor,
          parameterTypes = Seq("akka.io.SimpleDnsCache", "com.typesafe.config.Config"))),
      queryAllDeclaredConstructors = true),
    // pluggable through deprecated InetAddressDnsProvider
    ReflectConfigEntry(
      classOf[akka.io.SimpleDnsManager].getName,
      methods = Seq(ReflectMethod(NativeImageUtils.Constructor, parameterTypes = Seq("akka.io.DnsExt"))),
      queryAllDeclaredConstructors = true))

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
