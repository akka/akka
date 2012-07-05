/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.ticket

import akka.actor._
import akka.actor.remote.AkkaRemoteTest

class Ticket519Spec extends AkkaRemoteTest {
  "A remote TypedActor" should {
    "should handle remote future replies" in {
      val actor = TypedActor.newRemoteInstance(classOf[SamplePojo], classOf[SamplePojoImpl], 7000, host, port)
      val r = actor.someFutureString

      r.get must equal("foo")
    }
  }
}
