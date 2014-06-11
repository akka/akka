/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import scala.collection.immutable
import akka.actor.{ Props, ActorRef, Actor, ActorLogging }
import akka.http.model.HttpRequest
import akka.http.Http

/**
 * INTERNAL API
 */
private[http] class HttpHostConnector(normalizedSetup: Http.HostConnectorSetup, clientConnectionSettingsGroup: ActorRef)
  extends Actor with ActorLogging {

  def receive: Receive = ??? // TODO

}

/**
 * INTERNAL API
 */
private[http] object HttpHostConnector {
  def props(normalizedSetup: Http.HostConnectorSetup, clientConnectionSettingsGroup: ActorRef, dispatcher: String) =
    Props(classOf[HttpHostConnector], normalizedSetup, clientConnectionSettingsGroup) withDispatcher dispatcher

  final case class RequestContext(request: HttpRequest, retriesLeft: Int, redirectsLeft: Int, commander: ActorRef)
  final case class Disconnected(rescheduledRequestCount: Int)
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
    final case class Connected(openRequests: immutable.Queue[HttpRequest]) extends SlotState {
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
