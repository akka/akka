/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.ticket

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import akka.remote.{RemoteClient, RemoteServer}
import akka.actor._


class Ticket519Spec extends Spec with ShouldMatchers {

  val HOSTNAME = "localhost"
  val PORT = 6666

  describe("A remote TypedActor") {
    it("should handle remote future replies") {
      import akka.remote._

      val server = { val s = new RemoteServer; s.start(HOSTNAME,PORT); s}
      val actor = TypedActor.newRemoteInstance(classOf[SamplePojo], classOf[SamplePojoImpl],7000,HOSTNAME,PORT)
      val r = actor.someFutureString

      r.await.result.get should equal ("foo")
      TypedActor.stop(actor)
      server.shutdown
    }
  }
}
