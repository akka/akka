/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.ActorSystem
import akka.actor.DynamicAccess
import akka.event.EventStream
import akka.testkit.NativeImageUtils
import akka.testkit.NativeImageUtils.Constructor
import akka.testkit.NativeImageUtils.ReflectConfigEntry
import akka.testkit.NativeImageUtils.ReflectMethod
import com.typesafe.config.Config
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object NativeImageMetadataSpec {

  val metadataDir = NativeImageUtils.metadataDirFor("akka-remote")

  val additionalEntries = Seq(
    // akka.remote.watch-failure-detector.implementation-class (and then also in akka-cluster)
    ReflectConfigEntry(
      classOf[PhiAccrualFailureDetector].getName,
      methods = Seq(ReflectMethod(Constructor, Seq(classOf[Config].getName, classOf[EventStream].getName)))),
    // ssl-engine-provider
    ReflectConfigEntry(
      classOf[akka.remote.artery.tcp.ConfigSSLEngineProvider].getName,
      methods = Seq(ReflectMethod(Constructor, Seq(classOf[ActorSystem].getName)))),
    // used by akka-cluster but defined here
    ReflectConfigEntry(
      classOf[akka.remote.DeadlineFailureDetector].getName,
      methods =
        Seq(ReflectMethod(Constructor, parameterTypes = Seq(classOf[Config].getName, classOf[EventStream].getName)))),
    ReflectConfigEntry(
      classOf[RemoteActorRefProvider].getName,
      methods = Seq(
        ReflectMethod(
          Constructor,
          Seq(
            classOf[java.lang.String].getName,
            classOf[ActorSystem.Settings].getName,
            classOf[EventStream].getName,
            classOf[DynamicAccess].getName)))))

  val modulePackages = Seq("akka.remote")

  // run this to regenerate metadata 'akka-remote/Test/runMain akka.remote.NativeImageMetadataSpec'
  def main(args: Array[String]): Unit = {
    NativeImageUtils.writeMetadata(metadataDir, additionalEntries, modulePackages)
  }
}

class NativeImageMetadataSpec extends AnyWordSpec with Matchers {
  import NativeImageMetadataSpec._

  "Native-image metadata for akka-remote" should {

    "be up to date" in {
      val (existing, current) = NativeImageUtils.verifyMetadata(metadataDir, additionalEntries, modulePackages)
      existing should ===(current)
    }
  }

}
