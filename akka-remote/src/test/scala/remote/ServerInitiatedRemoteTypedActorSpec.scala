/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import se.scalablesolutions.akka.remote.{RemoteServer, RemoteClient}
import se.scalablesolutions.akka.actor._

object ServerInitiatedRemoteTypedActorSpec {
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null

  class SimpleActor extends Actor {
    def receive = {
      case _ => println("received message")
    }
  }


}

class ServerInitiatedRemoteTypedActorSpec extends JUnitSuite {
  import ServerInitiatedRemoteTypedActorSpec._
  private val unit = TimeUnit.MILLISECONDS


  @Before
  def init {
    server = new RemoteServer()
    server.start(HOSTNAME, PORT)
    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  @After
  def finished {
    try {
      server.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }


  @Test
  def shouldSendWithBang  {

   /*
    val clientManangedTypedActor = TypedActor.newRemoteInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000, HOSTNAME, PORT)
    clientManangedTypedActor.requestReply("test-string")
    Thread.sleep(2000)
    println("###########")
     */
    /*
    trace()
    val actor = Actor.actorOf[SimpleActor].start
    server.register("simple-actor", actor)
    val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
    server.registerTypedActor("typed-actor-service", typedActor)
    println("### registered actor")
    trace()

    //val actorRef = RemoteActorRef("typed-actor-service", classOf[RemoteTypedActorOneImpl].getName,  HOSTNAME, PORT, 5000L, None)
    //val myActor = TypedActor.createProxyForRemoteActorRef(classOf[RemoteTypedActorOne], actorRef)
    val myActor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", classOf[RemoteTypedActorOneImpl].getName, 5000L, HOSTNAME, PORT)
    println("### call one way")
    myActor.oneWay()
    Thread.sleep(3000)
    println("### call one way - done")
       */
    //assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
    //actor.stop
    /*  */
  }


}

