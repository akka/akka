package akka.actor.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.dispatch.Dispatchers
import akka.remote. {NettyRemoteSupport, RemoteServer, RemoteClient}
import akka.actor. {RemoteActorRef, ActorRegistry, ActorRef, Actor}
import akka.actor.Actor._

class ExpectedRemoteProblem(msg: String) extends RuntimeException(msg)

object RemoteActorSpecActorUnidirectional {
  val latch = new CountDownLatch(1)
}
class RemoteActorSpecActorUnidirectional extends Actor {
  self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)

  def receive = {
    case "OneWay" =>
      RemoteActorSpecActorUnidirectional.latch.countDown
  }
}

class RemoteActorSpecActorBidirectional extends Actor {
  def receive = {
    case "Hello" =>
      self.reply("World")
    case "Failure" => throw new ExpectedRemoteProblem("expected")
  }
}

class SendOneWayAndReplyReceiverActor extends Actor {
  def receive = {
    case "Hello" =>
      self.reply("World")
  }
}

class CountDownActor(latch: CountDownLatch) extends Actor {
  def receive = {
    case "World" => latch.countDown
  }
}

object SendOneWayAndReplySenderActor {
  val latch = new CountDownLatch(1)
}
class SendOneWayAndReplySenderActor extends Actor {
  var state: Option[AnyRef] = None
  var sendTo: ActorRef = _
  var latch: CountDownLatch = _

  def sendOff = sendTo ! "Hello"

  def receive = {
    case msg: AnyRef =>
      state = Some(msg)
      SendOneWayAndReplySenderActor.latch.countDown
  }
}

class MyActorCustomConstructor extends Actor {
  var prefix = "default-"
  var count = 0
  def receive = {
    case "incrPrefix" => count += 1; prefix = "" + count + "-"
    case msg: String => self.reply(prefix + msg)
  }
}

@RunWith(classOf[JUnitRunner])
class ClientInitiatedRemoteActorSpec extends
  WordSpec with
  MustMatchers with
  BeforeAndAfterAll with
  BeforeAndAfterEach {

  var optimizeLocal_? = ActorRegistry.remote.asInstanceOf[NettyRemoteSupport].optimizeLocalScoped_?

  override def beforeAll() {
    ActorRegistry.remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(false) //Can't run the test if we're eliminating all remote calls
    ActorRegistry.remote.start()
  }

  override def afterAll() {
    ActorRegistry.remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(optimizeLocal_?) //Reset optimizelocal after all tests
    ActorRegistry.shutdownAll
  }

  override def afterEach() {
    ActorRegistry.shutdownAll
    super.afterEach
  }

  "ClientInitiatedRemoteActor" should {
   val unit = TimeUnit.MILLISECONDS
   val (host, port) = (ActorRegistry.remote.hostname,ActorRegistry.remote.port)

   "shouldSendOneWay" in {
      val clientManaged = actorOf[RemoteActorSpecActorUnidirectional](host,port).start
      clientManaged must not be null
      clientManaged.getClass must be (classOf[RemoteActorRef])
      clientManaged ! "OneWay"
      RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS) must be (true)
      clientManaged.stop
    }

    "shouldSendOneWayAndReceiveReply" in {
      val latch = new CountDownLatch(1)
      val actor = actorOf[SendOneWayAndReplyReceiverActor](host,port).start
      implicit val sender = Some(actorOf(new CountDownActor(latch)).start)

      actor ! "Hello"

      latch.await(3,TimeUnit.SECONDS) must be (true)
    }

    "shouldSendBangBangMessageAndReceiveReply" in {
      val actor = actorOf[RemoteActorSpecActorBidirectional](host,port).start
      val result = actor !! "Hello"
      "World" must equal (result.get.asInstanceOf[String])
      actor.stop
    }

    "shouldSendBangBangMessageAndReceiveReplyConcurrently" in {
      val actors = (1 to 10).map(num => { actorOf[RemoteActorSpecActorBidirectional](host,port).start }).toList
      actors.map(_ !!! "Hello") foreach { future =>
        "World" must equal (future.await.result.asInstanceOf[Option[String]].get)
      }
      actors.foreach(_.stop)
    }

    "shouldRegisterActorByUuid" in {
      val actor1 = actorOf[MyActorCustomConstructor](host, port).start
      val actor2 = actorOf[MyActorCustomConstructor](host, port).start

      actor1 ! "incrPrefix"

      (actor1 !! "test").get must equal ("1-test")

      actor1 ! "incrPrefix"

      (actor1 !! "test").get must equal ("2-test")

      (actor2 !! "test").get must equal ("default-test")

      actor1.stop
      actor2.stop
    }

    "shouldSendAndReceiveRemoteException" in {

      val actor = actorOf[RemoteActorSpecActorBidirectional](host, port).start
      try {
        implicit val timeout = 500000000L
        val f = (actor !!! "Failure").await.resultOrException
        fail("Shouldn't get here!!!")
      } catch {
        case e: ExpectedRemoteProblem =>
      }
      actor.stop
    }
  }
}
