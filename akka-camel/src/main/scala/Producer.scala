/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import CamelMessageConversion.toExchangeAdapter

import org.apache.camel._
import org.apache.camel.processor.SendProcessor
import org.apache.camel.spi.Synchronization

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.dispatch.CompletableFuture
import se.scalablesolutions.akka.util.Logging

/**
 * Mixed in by Actor implementations that produce messages to Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Producer { this: Actor =>

  private val headersToCopyDefault = Set(Message.MessageExchangeId)

  private lazy val endpoint = CamelContextManager.context.getEndpoint(endpointUri)

  private lazy val processor = createProcessor

  /**
   * If set to false (default), this producer expects a response message from the Camel endpoint.
   * If set to true, this producer communicates with the Camel endpoint with an in-only message
   * exchange pattern (fire and forget).
   */
  def oneway: Boolean = false

  /**
   * Returns the Camel endpoint URI to produce messages to.
   */
  def endpointUri: String

  /**
   * Returns the names of message headers to copy from a request message to a response message.
   * By default only the Message.MessageExchangeId is copied. Applications may override this to
   * define an application-specific set of message headers to copy.
   */
  def headersToCopy: Set[String] = headersToCopyDefault

  /**
   * ...
   */
  override def shutdown {
    processor.stop
  }

  protected def produceOneway(msg: Any): Unit = {
    val exchange = createInOnlyExchange.fromRequestMessage(Message.canonicalize(msg))
    processor.process(exchange, new AsyncCallback {
      def done(doneSync: Boolean): Unit = { /* ignore because it's an in-only exchange */ }
    })
  }

  protected def produceTwoway(msg: Any): Unit = {
    val cmsg = Message.canonicalize(msg)
    val exchange = createInOutExchange.fromRequestMessage(cmsg)
    processor.process(exchange, new AsyncCallback {
      def done(doneSync: Boolean): Unit = {
        val response = if (exchange.isFailed)
          exchange.toFailureMessage(cmsg.headers(headersToCopy))
        else
          exchange.toResponseMessage(cmsg.headers(headersToCopy))
        self.reply(response)
      }
    })
  }

  protected def produce: Receive = {
    case msg => if (oneway) {
      produceOneway(msg)
    } else {
      val result = produceTwoway(msg)
    }
  }

  /**
   * Default implementation of Actor.receive
   */
  protected def receive = produce

  /**
   * Creates a new in-only Exchange.
   */
  protected def createInOnlyExchange: Exchange = createExchange(ExchangePattern.InOnly)

  /**
   * Creates a new in-out Exchange.
   */
  protected def createInOutExchange: Exchange = createExchange(ExchangePattern.InOut)

  /**
   * ...
   */
  protected def createExchange(pattern: ExchangePattern): Exchange = endpoint.createExchange(pattern)

  /**
   * 
   */
  private def createProcessor = {
    val sendProcessor = new SendProcessor(endpoint)
    sendProcessor.start
    sendProcessor
  }
}

/**
 * A one-way producer.
 *
 * @author Martin Krasser
 */
trait Oneway extends Producer { this: Actor =>
  override def oneway = true
}

