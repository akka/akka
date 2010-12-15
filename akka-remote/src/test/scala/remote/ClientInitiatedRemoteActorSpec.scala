package akka.actor.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import akka.dispatch.Dispatchers
import akka.remote. {NettyRemoteSupport, RemoteServer, RemoteClient}
import akka.actor. {RemoteActorRef, ActorRegistryInstance, ActorRef, Actor}

class ExpectedRemoteProblem extends RuntimeException

object ClientInitiatedRemoteActorSpec {
  case class Send(actor: Actor)

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
      case "Failure" => throw new ExpectedRemoteProblem
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
}

class ClientInitiatedRemoteActorSpec extends JUnitSuite {
  import ClientInitiatedRemoteActorSpec._
  akka.config.Config.config

  val HOSTNAME = "localhost"
  val PORT1 = 9990
  val PORT2 = 9991
  var s1,s2: ActorRegistryInstance = null

  private val unit = TimeUnit.MILLISECONDS

  @Before
  def init() {
    s1 = new ActorRegistryInstance(Some(new NettyRemoteSupport(_)))
    s2 = new ActorRegistryInstance(Some(new NettyRemoteSupport(_)))
    s1.remote.start(HOSTNAME, PORT1)
    s2.remote.start(HOSTNAME, PORT2)
    Thread.sleep(2000)
  }

  @After
  def finished() {
    s1.remote.shutdown
    s2.remote.shutdown
    s1.shutdownAll
    s2.shutdownAll
    Thread.sleep(1000)
  }

  @Test
  def shouldSendOneWay = {
    val clientManaged = s1.actorOf[RemoteActorSpecActorUnidirectional](HOSTNAME,PORT2).start
    //implicit val self = Some(s2.actorOf[RemoteActorSpecActorUnidirectional].start)
    assert(clientManaged ne null)
    assert(clientManaged.getClass.equals(classOf[RemoteActorRef]))
    clientManaged ! "OneWay"
    assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
    clientManaged.stop
  }


  @Test
  def shouldSendOneWayAndReceiveReply = {
    val latch = new CountDownLatch(1)
    val actor = s2.actorOf[SendOneWayAndReplyReceiverActor](HOSTNAME, PORT1).start
    implicit val sender = Some(s1.actorOf(new CountDownActor(latch)).start)

    actor ! "OneWay"

    assert(latch.await(3,TimeUnit.SECONDS))
  }

  @Test
  def shouldSendBangBangMessageAndReceiveReply = {
    val actor = s2.actorOf[RemoteActorSpecActorBidirectional](HOSTNAME, PORT1).start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def shouldSendBangBangMessageAndReceiveReplyConcurrently = {
    val actors = (1 to 10).map(num => { s2.actorOf[RemoteActorSpecActorBidirectional](HOSTNAME, PORT1).start }).toList
    actors.map(_ !!! "Hello").foreach(future => assert("World" === future.await.result.asInstanceOf[Option[String]].get))
    actors.foreach(_.stop)
  }

  @Test
  def shouldRegisterActorByUuid {
    val actor1 = s2.actorOf[MyActorCustomConstructor](HOSTNAME, PORT1).start
    actor1 ! "incrPrefix"
    assert((actor1 !! "test").get === "1-test")
    actor1 ! "incrPrefix"
    assert((actor1 !! "test").get === "2-test")

    val actor2 = s2.actorOf[MyActorCustomConstructor](HOSTNAME, PORT1).start

    assert((actor2 !! "test").get === "default-test")

    actor1.stop
    actor2.stop
  }

  @Test(expected=classOf[ExpectedRemoteProblem])
  def shouldSendAndReceiveRemoteException {
    implicit val timeout = 500000000L
    val actor = s2.actorOf[RemoteActorSpecActorBidirectional](HOSTNAME, PORT1).start
    actor !! "Failure"
    actor.stop
  }
}

