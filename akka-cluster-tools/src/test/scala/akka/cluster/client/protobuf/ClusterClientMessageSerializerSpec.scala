/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.client.protobuf

import akka.actor.ExtendedActorSystem
import akka.testkit.AkkaSpec
import akka.cluster.client.ClusterReceptionist.Internal._

class ClusterClientMessageSerializerSpec extends AkkaSpec {

  val serializer = new ClusterClientMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, serializer.manifest(obj))
    ref should ===(obj)
  }

  "ClusterClientMessages" must {

    "be serializable" in {
      val contactPoints = Vector(
        "akka.tcp://system@node-1:2552/system/receptionist",
        "akka.tcp://system@node-2:2552/system/receptionist",
        "akka.tcp://system@node-3:2552/system/receptionist")
      checkSerialization(Contacts(contactPoints))
      checkSerialization(GetContacts)
      checkSerialization(Heartbeat)
      checkSerialization(HeartbeatRsp)
    }
  }
}
