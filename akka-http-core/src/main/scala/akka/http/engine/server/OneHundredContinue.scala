/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import scala.annotation.tailrec
import akka.stream.actor.{ ActorPublisherMessage, ActorPublisher }

/**
 * The `Expect: 100-continue` header has a special status in HTTP.
 * It allows the client to send an `Expect: 100-continue` header with the request and then pause request sending
 * (i.e. hold back sending the request entity). The server reads the request headers, determines whether it wants to
 * accept the request and responds with
 *
 * - `417 Expectation Failed`, if it doesn't support the `100-continue` expectation
 *   (or if the `Expect` header contains other, unsupported expectations).
 * - a `100 Continue` response,
 *   if it is ready to accept the request entity and the client should go ahead with sending it
 * - a final response (like a 4xx to signal some client-side error
 *   (e.g. if the request entity length is beyond the configured limit) or a 3xx redirect)
 *
 * Only if the client receives a `100 Continue` response from the server is it allowed to continue sending the request
 * entity. In this case it will receive another response after having completed request sending.
 * So this special feature breaks the normal "one request - one response" logic of HTTP!
 * It therefore requires special handling in all HTTP stacks (client- and server-side).
 *
 * For us this means:
 *
 * - on the server-side:
 *   After having read a `Expect: 100-continue` header with the request we package up an `HttpRequest` instance and send
 *   it through to the application. Only when (and if) the application then requests data from the entity stream do we
 *   send out a `100 Continue` response and continue reading the request entity.
 *   The application can therefore determine itself whether it wants the client to send the request entity
 *   by deciding whether to look at the request entity data stream or not.
 *   If the application sends a response *without* having looked at the request entity the client receives this
 *   response *instead of* the `100 Continue` response and the server closes the connection afterwards.
 *
 * - on the client-side:
 *   If the user adds a `Expect: 100-continue` header to the request we need to hold back sending the entity until
 *   we've received a `100 Continue` response.
 */
private[engine] case object OneHundredContinue

private[engine] class OneHundredContinueSourceActor extends ActorPublisher[OneHundredContinue.type] {
  private var triggered = 0

  def receive = {
    case OneHundredContinue ⇒
      triggered += 1
      tryDispatch()

    case ActorPublisherMessage.Request(_) ⇒
      tryDispatch()

    case ActorPublisherMessage.Cancel ⇒
      context.stop(self)
  }

  @tailrec private def tryDispatch(): Unit =
    if (triggered > 0 && totalDemand > 0) {
      onNext(OneHundredContinue)
      triggered -= 1
      tryDispatch()
    }
}