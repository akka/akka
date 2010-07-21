package sample.camel

import collection.mutable.Set

import java.util.concurrent.CountDownLatch

import org.junit._

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActorRegistry, ActorRef, Actor}
import se.scalablesolutions.akka.camel.{CamelService, Message, Producer, Consumer}
import se.scalablesolutions.akka.routing.CyclicIterator
import se.scalablesolutions.akka.routing.Routing._
import org.scalatest.junit.JUnitSuite

/**
 * @author Martin Krasser
 */
@Ignore
class HttpConcurrencyTest extends JUnitSuite {
  import HttpConcurrencyTest._

  @Test def shouldProcessMessagesConcurrently = {
    val num = 50
    val latch1 = new CountDownLatch(num)
    val latch2 = new CountDownLatch(num)
    val latch3 = new CountDownLatch(num)
    val client1 = actorOf(new HttpClientActor("client1", latch1)).start
    val client2 = actorOf(new HttpClientActor("client2", latch2)).start
    val client3 = actorOf(new HttpClientActor("client3", latch3)).start
    for (i <- 1 to num) {
      client1 ! Message("client1", Map(Message.MessageExchangeId -> i))
      client2 ! Message("client2", Map(Message.MessageExchangeId -> i))
      client3 ! Message("client3", Map(Message.MessageExchangeId -> i))
    }
    latch1.await
    latch2.await
    latch3.await
    assert(num == (client1 !! "getCorrelationIdCount").as[Int].get)
    assert(num == (client2 !! "getCorrelationIdCount").as[Int].get)
    assert(num == (client3 !! "getCorrelationIdCount").as[Int].get)
  }
}

object HttpConcurrencyTest {
  var service: CamelService = _

  @BeforeClass
  def beforeClass = {
    service = CamelService.newInstance.load

    val workers = for (i <- 1 to 8) yield actorOf[HttpServerWorker].start
    val balancer = loadBalancerActor(new CyclicIterator(workers.toList))

    val completion = service.expectEndpointActivationCount(1)
    val server = actorOf(new HttpServerActor(balancer)).start
    completion.await
  }

  @AfterClass
  def afterClass = {
    service.unload
    ActorRegistry.shutdownAll
  }

  class HttpClientActor(label: String, latch: CountDownLatch) extends Actor with Producer {
    def endpointUri = "jetty:http://0.0.0.0:8855/echo"
    var correlationIds = Set[Any]()

    override protected def receive = {
      case "getCorrelationIdCount" => self.reply(correlationIds.size)
      case msg => super.receive(msg)
    }

    override protected def receiveAfterProduce = {
      case msg: Message => {
        val corr = msg.headers(Message.MessageExchangeId)
        val body = msg.bodyAs[String]
        correlationIds += corr
        assert(label == body)
        latch.countDown
        print(".")
      }
    }
  }

  class HttpServerActor(balancer: ActorRef) extends Actor with Consumer {
    def endpointUri = "jetty:http://0.0.0.0:8855/echo"

    def receive = {
      case msg => balancer forward msg
    }
  }

  class HttpServerWorker extends Actor {
    protected def receive = {
      case msg => {
        // slow processing
        Thread.sleep(100)
        self.reply(msg)
      }
    }
  }
}