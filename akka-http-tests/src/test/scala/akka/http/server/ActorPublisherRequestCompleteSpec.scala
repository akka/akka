package akka.http.server

import scala.concurrent.ExecutionContext
import scala.sys.process.Process

import org.scalatest.WordSpec
import org.scalatest.concurrent.ScalaFutures

import akka.actor.{ ActorSystem, Props, Terminated }
import akka.http.Http
import akka.http.marshalling.Marshaller
import akka.http.marshalling.ToResponseMarshaller
import akka.http.model.{ HttpCharsets, HttpEntity, HttpResponse, MediaTypes }
import akka.stream.ActorFlowMaterializer
import akka.stream.actor.{ ActorPublisher, ActorPublisherMessage }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.TestProbe
import akka.util.ByteString

class ActorPublisherRequestCompleteSpec extends WordSpec with Directives with ScalaFutures {

  implicit val system = ActorSystem("ActorPublisherRequestCompleteSpec")
  implicit val materi = ActorFlowMaterializer()
  import system.dispatcher

  "actor publisher" should {

    "be sent a Cancel message" when {

      "downstream has cancelled and upstream emitted some events" in
        httpServerWithActorPublisher(eventsAfterCancel = 2)

      "downstream has cancelled and upstream emitted no events" in
        httpServerWithActorPublisher(eventsAfterCancel = 0)
    }
  }

  def httpServerWithActorPublisher(eventsAfterCancel: Int) = {

    val probe = TestProbe()
    val pub = system.actorOf(Props(new BufferedPublisher))
    probe.watch(pub)

    val myRoute =
      path("test") {
        complete {
          Source(ActorPublisher[String](pub))
        }
      }

    //FIXME: Workaround https://github.com/akka/akka/issues/16972 is fixed
    val serverBinding = Http().bind(interface = "localhost", port = 0).to(Sink.foreach { conn ⇒
      conn.flow.join(myRoute).run()
    }).run()

    whenReady(serverBinding) { binding ⇒

      val process = queryEndpoint(binding.localAddress)
      Thread.sleep(1000)
      process.destroy()

      (0 to eventsAfterCancel) foreach { _ ⇒ pub ! "event" }
      probe.expectMsgClass(classOf[Terminated])
    }
  }

  implicit def stringSourceMarshaller(implicit ec: ExecutionContext): ToResponseMarshaller[Source[String, Unit]] =
    Marshaller.withFixedCharset(MediaTypes.`text/plain`, HttpCharsets.`UTF-8`) { messages ⇒
      HttpResponse(entity = HttpEntity.CloseDelimited(MediaTypes.`text/plain`, messages.map(ByteString.apply)))
    }

  class BufferedPublisher() extends ActorPublisher[String] {

    private val bufferSize = 16
    private var events = Vector.empty[String]

    override def receive = {
      case ev @ "event"                          ⇒ onEvent(ev)
      case ActorPublisherMessage.Request(demand) ⇒ publish(demand)
      case msg: ActorPublisherMessage            ⇒ context.stop(self)
    }

    private def onEvent(event: String): Unit = {
      events = (events :+ event).takeRight(bufferSize)
      if (isActive) publish(totalDemand)
    }

    private def publish(demand: Long) = {
      val (requested, remaining) = events.splitAt(demand.toInt)
      requested.foreach(onNext)
      events = remaining
    }
  }

  private def queryEndpoint(address: java.net.InetSocketAddress) = {
    val command = List(
      "curl",
      "-s",
      s"http://${address.getHostString}:${address.getPort}/test")
    Process(command).run()
  }

}
