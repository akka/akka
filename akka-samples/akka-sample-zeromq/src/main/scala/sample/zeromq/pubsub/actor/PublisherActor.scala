package sample.zeromq.pubsub.actor

import akka.actor.{ ActorSystem, Props, Actor }
import util.Random
import akka.zeromq._
import compat.Platform
import sample.zeromq.Util

class PublisherActor extends Actor {
  println("Binding...")

  private sealed case class PublishMessage()

  private val publisherSocket = ZeroMQExtension(context.system).newPubSocket(Bind("tcp://127.0.0.1:1234"))

  val random = new Random()
  val maxMessageSize = 100

  var counter = 0

  override def preStart() = {
    publishMessage()
  }

  def receive = {
    case m: ZMQMessage     ⇒ publisherSocket ! m; publishMessage()
    case p: PublishMessage ⇒ publishMessage()
    case _                 ⇒ throw new Exception("unknown command")
  }

  private def publishMessage() = {
    val modulo = 256
    if (counter % modulo == 0) {
      val message = Util.randomString(random, maxMessageSize)
      self ! ZMQMessage(Seq(Frame(message)))
    } else {
      self ! PublishMessage()
    }

    counter += 1
    if (counter >= 3000) {
      counter = 0
    }
  }

}
