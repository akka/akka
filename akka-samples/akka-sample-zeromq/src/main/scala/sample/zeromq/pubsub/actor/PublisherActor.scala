package sample.zeromq.pubsub.actor

import akka.actor.{ ActorSystem, Props, Actor }
import akka.util.ByteString
import akka.zeromq._
import util.Random
import compat.Platform
import sample.zeromq.Util

class PublisherActor extends Actor {
  println("Binding...")

  private sealed case class PublishMessage()

  private val publisherSocket = ZeroMQExtension(context.system).newPubSocket(Bind("tcp://127.0.0.1:1234"))

  val random = new Random()
  val maxMessageSize = 100
  val modulo = 256
  var counter = 0

  private def publishMessage() = {
    if (counter % modulo == 0) {
      val message = Util.randomString(random, maxMessageSize)
      self ! ZMQMessage(ByteString(message))
    } else {
      self ! PublishMessage()
    }

    counter = if (counter >= 2999) 0 else counter + 1
  }

  override def preStart() = {
    publishMessage()
  }

  def receive = {
    case m: ZMQMessage     ⇒ publisherSocket ! m; publishMessage()
    case p: PublishMessage ⇒ publishMessage()
  }

}
