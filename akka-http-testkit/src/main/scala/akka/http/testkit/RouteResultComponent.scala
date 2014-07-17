/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.testkit

import java.util.concurrent.CountDownLatch
import akka.http.model.HttpEntity.ChunkStreamPart

import concurrent.duration._
import scala.collection.mutable.ListBuffer
import akka.actor.{ ActorSystem, Status, ActorRefFactory, ActorRef }
import akka.testkit._
import akka.http.routing.{ RouteResult ⇒ RoutingRouteResult, _ }
import akka.http.model._

trait RouteResultComponent {

  def failTest(msg: String): Nothing

  /**
   * A receptacle for the response, rejections and potentially generated response chunks created by a route.
   */
  class RouteResult(timeout: FiniteDuration)(implicit actorRefFactory: ActorRefFactory) {
    private[this] var _response: Option[HttpResponse] = None
    private[this] var _rejections: Option[List[Rejection]] = None
    private[this] val _chunks = ListBuffer.empty[ChunkStreamPart]
    private[this] var _closingExtension = ""
    private[this] var _trailer: List[HttpHeader] = Nil
    private[this] val latch = new CountDownLatch(1)
    private[this] var virginal = true

    private[testkit] def handleResult(result: RoutingRouteResult): Unit = result match {
      case CompleteWith(resp: HttpResponse) ⇒
        saveResult(Right(resp))
        latch.countDown()
      case Rejected(rejections) ⇒
        saveResult(Left(rejections))
        latch.countDown()
      case RouteException(error) ⇒
        sys.error("Route produced exception: " + error)
    }
    /*private[testkit] val handler = new UnregisteredActorRef(actorRefFactory) {
      def handle(message: Any)(implicit sender: ActorRef) {
        def verifiedSender =
          if (sender != null) sender else sys.error("Received message " + message + " from unknown sender (null)")
        message match {
          case HttpMessagePartWrapper(x: HttpResponse, _) ⇒
            saveResult(Right(x))
            latch.countDown()
          case Rejected(rejections) ⇒
            saveResult(Left(rejections))
            latch.countDown()
          case HttpMessagePartWrapper(ChunkedResponseStart(x), ack) ⇒
            saveResult(Right(x))
            ack.foreach(verifiedSender.tell(_, this))
          case HttpMessagePartWrapper(x: MessageChunk, ack) ⇒
            synchronized { _chunks += x }
            ack.foreach(verifiedSender.tell(_, this))
          case HttpMessagePartWrapper(ChunkedMessageEnd(extension, trailer), _) ⇒
            synchronized { _closingExtension = extension; _trailer = trailer }
            latch.countDown()
          case Status.Failure(error) ⇒
            sys.error("Route produced exception: " + error)
          case x ⇒
            sys.error("Received invalid route response: " + x)
        }
      }
    }*/

    private[testkit] def awaitResult: this.type = {
      latch.await(timeout.toMillis, MILLISECONDS)
      this
    }

    private def saveResult(result: Either[List[Rejection], HttpResponse]): Unit = {
      synchronized {
        if (!virginal) failTest("Route completed/rejected more than once")
        result match {
          case Right(resp) ⇒ _response = Some(resp)
          case Left(rejs)  ⇒ _rejections = Some(rejs)
        }
        virginal = false
      }
    }

    private def failNotCompletedNotRejected(): Nothing =
      failTest("Request was neither completed nor rejected within " + timeout)

    def handled: Boolean = synchronized { _response.isDefined }
    def response: HttpResponse = synchronized {
      _response.getOrElse {
        _rejections.foreach {
          RejectionHandler.applyTransformations(_) match {
            case Nil ⇒ failTest("Request was not handled")
            case r   ⇒ failTest("Request was rejected with " + r)
          }
        }
        failNotCompletedNotRejected()
      }
    }
    def rejections: List[Rejection] = synchronized {
      _rejections.getOrElse {
        _response.foreach(resp ⇒ failTest("Request was not rejected, response was " + resp))
        failNotCompletedNotRejected()
      }
    }
    def chunks: List[ChunkStreamPart] = synchronized { _chunks.toList }
    def closingExtension = synchronized { _closingExtension }
    def trailer = synchronized { _trailer }

    def ~>[T](f: RouteResult ⇒ T): T = f(this)
  }

  case class RouteTestTimeout(duration: FiniteDuration)

  object RouteTestTimeout {
    implicit def default(implicit system: ActorSystem) = RouteTestTimeout(1.second dilated)
  }
}