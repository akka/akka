/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.event.Logging
import akka.http.impl.util.ExampleHttpContexts
import akka.http.scaladsl.model._
import akka.stream._
import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

class Http2BindingViaConfigSpec extends AkkaSpec("""
    akka.http.server.preview.enable-http2 = on
    
    akka.loglevel = DEBUG
    akka.log-dead-letters = off
  """) {
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
  var binding: Future[Http.ServerBinding] = _ // initialized atStartup

  val helloWorldHandler: HttpRequest ⇒ Future[HttpResponse] =
    _ ⇒ Future(HttpResponse(entity = "Hello!"))

  "akka.http.server.enable-http2" should {
    "bind using plain JTT{ when provided ConnectionContext is HTTP (not HTTPS)" in {
      // since we're in akka-http core here, the http2 support is not available on classpath,
      // so the setting + binding should fail

      var binding: Future[Http.ServerBinding] = null
      try {
        val p = TestProbe()
        system.eventStream.subscribe(p.ref, classOf[Logging.Debug])

        binding = Http().bindAndHandleAsync(helloWorldHandler, host, port)
        fishForDebugMessage(p, "binding using plain HTTP")
      } finally if (binding ne null) binding.map(_.unbind())
    }
    "bind using HTTP/2 with HttpsConnectionContext provided" in {
      // since we're in akka-http core here, the http2 support is not available on classpath,
      // so the setting + binding should fail

      var binding: Future[Http.ServerBinding] = null
      try {
        val p = TestProbe()
        system.eventStream.subscribe(p.ref, classOf[Logging.Debug])

        val connectionContext = ExampleHttpContexts.exampleServerContext
        binding = Http().bindAndHandleAsync(
          helloWorldHandler,
          host, port, connectionContext)

        // TODO we currently don't verify it really bound as h2, since we don't have a client lib
        fishForDebugMessage(p, "Binding server using HTTP/2")
      } finally if (binding ne null) binding.map(_.unbind())
    }
  }

  "Http2Shadow" should {
    "have ShadowHttp2Ext type aligned with real Http2Ext" in {
      val ext: Http2Ext = Http2()
      // the Shadow is a structural type, so this assignment only works 
      // if all the methods it requires exist on the real class
      val shadow: akka.http.impl.engine.Http2Shadow.ShadowHttp2Ext = ext
    }
  }

  private def fishForDebugMessage(a: TestProbe, messagePrefix: String, max: Duration = 3.seconds) {
    a.fishForMessage(max, hint = "expected debug message part: " + messagePrefix) {
      case Logging.Debug(_, _, msg: String) if msg contains messagePrefix ⇒ true
      case other ⇒ false
    }
  }

}
