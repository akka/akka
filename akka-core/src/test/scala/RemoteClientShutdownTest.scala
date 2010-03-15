package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.remote.RemoteNode
import se.scalablesolutions.akka.actor._
import Actor.Sender.Self

import org.scalatest.junit.JUnitSuite
import org.junit.Test
/*
class RemoteClientShutdownTest extends JUnitSuite {
  @Test def shouldShutdownRemoteClient = {
    RemoteNode.start("localhost", 9999)

    var remote = new TravelingActor
    remote.start
    remote ! "sending a remote message"
    remote.stop

    Thread.sleep(1000)
    RemoteNode.shutdown
    println("======= REMOTE CLIENT SHUT DOWN FINE =======")
    assert(true)
  }
}

class TravelingActor extends RemoteActor("localhost", 9999) {
  def receive = {
    case _ => log.info("message received")
  }
}
*/