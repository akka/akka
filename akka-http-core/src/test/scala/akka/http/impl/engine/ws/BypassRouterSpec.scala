/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.impl.engine.ws

import akka.stream.testkit.AkkaSpec
import scala.concurrent.Await
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl._
import akka.stream.scaladsl._
import akka.stream._
import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import org.scalactic.ConversionCheckedTripleEquals
import akka.stream.testkit.Utils

class BypassRouterSpec extends AkkaSpec("akka.stream.materializer.debug.fuzzing-mode = off") with ScalaFutures with ConversionCheckedTripleEquals {

  implicit val patience = PatienceConfig(3.seconds)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  "BypassRouter" must {

    "work without double pull-ing some ports" in Utils.assertAllStagesStopped {
      val bindingFuture = Http().bindAndHandleSync({
        case HttpRequest(_, _, headers, _, _) ⇒
          val upgrade = headers.collectFirst { case u: UpgradeToWebsocket ⇒ u }.get
          upgrade.handleMessages(Flow.apply, None)
      }, interface = "localhost", port = 8080)
      val binding = Await.result(bindingFuture, 3.seconds)

      val N = 100
      val (response, count) = Http().singleWebsocketRequest(
        WebsocketRequest("ws://127.0.0.1:8080"),
        Flow.fromSinkAndSourceMat(
          Sink.fold(0)((n, _: Message) ⇒ n + 1),
          Source.repeat(TextMessage("hello")).take(N))(Keep.left))

      count.futureValue should ===(N)
      binding.unbind()
    }

  }

}
