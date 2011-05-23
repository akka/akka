/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import akka.config.Supervision.{ Permanent, Temporary }

object IOActorSpec {

  class EchoServer(val host: String, val port: Int) extends Actor {

    self.lifeCycle = Permanent

    var ioManager: ActorRef = _

    override def preStart = {
      ioManager = self startLink (Actor.actorOf(new IOManager))
      ioManager ! IO.CreateServer(self, host, port)
    }

    def receive = {
      case IO.NewConnection ⇒
        self reply IO.AcceptConnection(self, self startLink (Actor.actorOf(new EchoServerWorker(ioManager))))
    }

  }

  class EchoServerWorker(val ioManager: ActorRef) extends Actor {

    self.lifeCycle = Temporary

    def receive = {
      case IO.Read(bytes) ⇒ self reply IO.Write(self, bytes)
      case IO.Closed      ⇒ self.stop
    }

  }

}

class IOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import IOActorSpec._

  "an IO Actor" must {
    "run" in {
      val actor = Actor.actorOf(new EchoServer("localhost", 8064)).start
      Thread.sleep(600000)
    }
  }

}
