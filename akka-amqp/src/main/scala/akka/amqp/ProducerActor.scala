/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import com.rabbitmq.client._

import com.rabbitmq.client.AMQP.BasicProperties
import akka.dispatch.Future
import java.io.IOException
import akka.actor.{ Status, ActorRef }
import akka.util.NonFatal

private[amqp] class ProducerActor(producerParameters: ProducerParameters)
  extends FaultTolerantChannelActor(
    producerParameters.exchangeParameters, producerParameters.channelParameters) {

  import producerParameters._

  val exchangeName = exchangeParameters.flatMap(params ⇒ Option(params.exchangeName))

  def specificMessageHandler = {
    /**
     * if we get an AMQP message, try to publish it to the exchange
     */
    case message @ Message(payload, routingKey, mandatory, immediate, properties) ⇒
      channel orElse errorCallbackActor match {
        case Some(actor: ActorRef) ⇒ actor ! message
        case Some(cf: Future[Channel]) ⇒
          val replyTo = sender
          cf onComplete {
            case Right(c: Channel) ⇒ try {
              c.basicPublish(exchangeName.getOrElse(""), routingKey, mandatory, immediate, properties.orNull, payload.toArray)
            } catch {
              case e: IOException ⇒ replyTo ! Status.Failure(e)
            }
            case Left(e: IOException) ⇒ replyTo ! Status.Failure(e)
            case Left(NonFatal(e)) ⇒
              log.error("producer {} failed to publish messsage {}", self, message)
              replyTo ! Status.Failure(e)
          }

        case None ⇒
          log.warning("Unable to send message [{}]", message)
          sender ! new AkkaAMQPException("Unable to send message [" + message + "], no channel or errorCallbackActor available.")
      }

    /**
     * ignore any other kind of message.  control message for the channel will be handled by another block
     * in the FaultTolerantChannelActor
     */
    case _ ⇒ ()
  }

  /**
   * implements the producer logic for setting up the channel.  if a return listener was defined in the producer params,
   * we use it to define what to do when publishing to the channel fails and the mandatory or immediate flags were set,
   * otherwise we create a generic one that recycles the channel.
   */
  protected def setupChannel(ch: Channel) {
    val replyTo = self
    returnListener match {
      case Some(listener) ⇒ ch.addReturnListener(listener)
      case None ⇒ ch.addReturnListener(new ReturnListener() {
        def handleReturn(
          replyCode: Int,
          replyText: String,
          exchange: String,
          routingKey: String,
          properties: BasicProperties,
          body: Array[Byte]) {
          val e = new MessageNotDeliveredException(
            "Could not deliver message [" + body +
              "] with reply code [" + replyCode +
              "] with reply text [" + replyText +
              "] and routing key [" + routingKey +
              "] to exchange [" + exchange + "] - restarting channel")
          channel = None
          replyTo ! Failure(e)
        }

      })
    }
  }

  override def toString =
    "Producer[actor = " + self +
      ", exchangeParameters=" + exchangeParameters + "]"

}

