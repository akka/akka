/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import scala.collection.immutable
import akka.actor.{ ActorRef, Actor, ActorLogging }
import akka.http.model.HttpRequest
import akka.http.Http

private[http] class HttpHostConnector(normalizedSetup: Http.HostConnectorSetup, clientConnectionSettingsGroup: ActorRef)
  extends Actor with ActorLogging {

  def receive: Receive = ??? // TODO

}

private[http] object HttpHostConnector {
  case class RequestContext(request: HttpRequest, retriesLeft: Int, redirectsLeft: Int, commander: ActorRef)
  case class Disconnected(rescheduledRequestCount: Int)
  case object RequestCompleted
  case object DemandIdleShutdown

  sealed trait SlotState {
    def enqueue(request: HttpRequest): SlotState
    def dequeueOne: SlotState
    def openRequestCount: Int
  }
  object SlotState {
    sealed abstract class WithoutRequests extends SlotState {
      def enqueue(request: HttpRequest) = Connected(immutable.Queue(request))
      def dequeueOne = throw new IllegalStateException
      def openRequestCount = 0
    }
    case object Unconnected extends WithoutRequests
    case class Connected(openRequests: immutable.Queue[HttpRequest]) extends SlotState {
      require(openRequests.nonEmpty)
      def enqueue(request: HttpRequest) = Connected(openRequests.enqueue(request))
      def dequeueOne = {
        val reqs = openRequests.tail
        if (reqs.isEmpty) Idle
        else Connected(reqs)
      }
      def openRequestCount = openRequests.size
    }
    case object Idle extends WithoutRequests
  }
}
