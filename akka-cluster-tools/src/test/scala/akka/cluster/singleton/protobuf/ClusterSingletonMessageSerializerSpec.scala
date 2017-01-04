/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.singleton.protobuf

import akka.actor.ExtendedActorSystem
import akka.testkit.AkkaSpec
import akka.cluster.singleton.ClusterSingletonManager.Internal.HandOverDone
import akka.cluster.singleton.ClusterSingletonManager.Internal.HandOverInProgress
import akka.cluster.singleton.ClusterSingletonManager.Internal.HandOverToMe
import akka.cluster.singleton.ClusterSingletonManager.Internal.TakeOverFromMe

class ClusterSingletonMessageSerializerSpec extends AkkaSpec {

  val serializer = new ClusterSingletonMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, serializer.manifest(obj))
    ref should ===(obj)
  }

  "ClusterSingletonMessages" must {

    "be serializable" in {
      checkSerialization(HandOverDone)
      checkSerialization(HandOverInProgress)
      checkSerialization(HandOverToMe)
      checkSerialization(TakeOverFromMe)
    }
  }
}
