/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal

import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.Behavior
import akka.cluster.typed.internal.receptionist.ClusterReceptionist
import akka.serialization.SerializationExtension
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class AkkaClusterTypedSerializerSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  val ref = spawn(Behavior.empty[String])
  val untypedSystem = system.toUntyped
  val serializer = new AkkaClusterTypedSerializer(untypedSystem.asInstanceOf[ExtendedActorSystem])

  "AkkaClusterTypedSerializer" must {

    Seq("ReceptionistEntry" -> ClusterReceptionist.Entry(ref, 666L)).foreach {
      case (scenario, item) =>
        s"resolve serializer for $scenario" in {
          val serializer = SerializationExtension(untypedSystem)
          serializer.serializerFor(item.getClass).getClass should be(classOf[AkkaClusterTypedSerializer])
        }

        s"serialize and de-serialize $scenario" in {
          verifySerialization(item)
        }
    }
  }

  def verifySerialization(msg: AnyRef): Unit = {
    serializer.fromBinary(serializer.toBinary(msg), serializer.manifest(msg)) should be(msg)
  }

}
