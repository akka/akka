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

import scala.concurrent.duration._

import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure }

import akka.actor.{ ActorSystem, ActorRefFactory }

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.http.routing.util.StreamUtils._

import akka.testkit._

import akka.http.model.HttpEntity.ChunkStreamPart
import akka.http.routing.{ RouteResult ⇒ RoutingRouteResult, _ }
import akka.http.model._

trait RouteResultComponent {

  def failTest(msg: String): Nothing

  /**
   * A receptacle for the response, rejections and potentially generated response chunks created by a route.
   */
  class RouteResult(timeout: FiniteDuration, materializer: FlowMaterializer)(implicit actorRefFactory: ActorRefFactory) {
    private[this] var _response: Option[HttpResponse] = None
    private[this] var _rejections: Option[List[Rejection]] = None
    private[this] val latch = new CountDownLatch(1)
    private[this] var virginal = true

    private[testkit] def handleResult(result: RoutingRouteResult)(implicit ec: ExecutionContext): Unit = result match {
      case CompleteWith(resp: HttpResponse) ⇒
        saveResult(Right(resp))
        latch.countDown()
      case Rejected(rejections) ⇒
        saveResult(Left(rejections))
        latch.countDown()
      case RouteException(error) ⇒
        sys.error("Route produced exception: " + error)
      case DeferredResult(result) ⇒
        result.onComplete {
          case Success(r)  ⇒ handleResult(r)
          case Failure(ex) ⇒ handleResult(RouteException(ex))
        }
    }

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
    def retrieveResponse: HttpResponse = synchronized {
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

    def response: HttpResponse = retrieveResponse.copy(entity = entity)

    private[this] lazy val entityRecreator: () ⇒ HttpEntity =
      retrieveResponse.entity match {
        case s: HttpEntity.Strict ⇒ () ⇒ s
        case HttpEntity.Default(tpe, length, data) ⇒
          val dataChunks = awaitAllElements(data, materializer)

          () ⇒ HttpEntity.Default(tpe, length, Flow(dataChunks).toPublisher(materializer))

          case HttpEntity.CloseDelimited(tpe, data) ⇒
          val dataChunks = awaitAllElements(data, materializer)

          () ⇒ HttpEntity.CloseDelimited(tpe, Flow(dataChunks).toPublisher(materializer))

          case HttpEntity.Chunked(tpe, chunks) ⇒
          val dataChunks = awaitAllElements(chunks, materializer)

          () ⇒ HttpEntity.Chunked(tpe, Flow(dataChunks).toPublisher(materializer))
      }
    /** Returns an entity from which you can everytime start to read anew */
    def entity: HttpEntity = entityRecreator()

    lazy val chunks: List[ChunkStreamPart] =
      entity match {
        case HttpEntity.Chunked(_, chunks) ⇒ awaitAllElements[ChunkStreamPart](chunks, materializer).toList
        case _                             ⇒ Nil
      }

    def ~>[T](f: RouteResult ⇒ T): T = f(this)
  }

  case class RouteTestTimeout(duration: FiniteDuration)

  object RouteTestTimeout {
    implicit def default(implicit system: ActorSystem) = RouteTestTimeout(1.second dilated)
  }
}