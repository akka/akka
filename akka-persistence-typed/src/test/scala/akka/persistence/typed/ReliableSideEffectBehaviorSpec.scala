/*
 * written as a Proof of Concept by Cyrille Chepelov
 *
 */

package akka.persistence.typed

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.persistence.typed.scaladsl.Effect
import akka.util.Timeout
import akka.Done
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll, Matchers}

import scala.collection.{immutable => im}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

object ReliableSideEffectBehaviorSpec {
  sealed trait Protocol
  sealed trait Commands extends Protocol
  case class AppendString(message: String)(val replyTo: ActorRef[Done]) extends Commands
  case class RemoveString(message: String)(val replyTo: ActorRef[Done]) extends Commands
  case class ClearMessages()(val replyTo: ActorRef[Done]) extends Commands
  case class Stop()(val replyTo: ActorRef[Done]) extends Commands

  sealed trait Queries extends Protocol
  final case class GetStrings()(val replyTo: ActorRef[Vector[String]]) extends Queries

  final case class CauseGrief(message: String) extends Commands

  final case class State(messages: Vector[String])
  object State {
    def empty: State = State(Vector.empty)
  }

  sealed trait Event extends Serializable
  final case class AddedStringEvent(str: String) extends Event
  final case class RemovedStringEvent(str: String) extends Event
  case object ClearedEvent extends Event

  sealed trait Notification
  final case class AddedString(persistentId: String, message: String) extends Notification
  final case class RemovedString(persistentId: String, message: String) extends Notification

  private def commandHandler: (ActorContext[Protocol], State, Protocol) ⇒ Effect[Event, State] = {
    case (ctx, state, cmd: Stop) ⇒
      Effect.stop
        .thenRun {
          _ ⇒ cmd.replyTo ! Done
        }

    case (ctx, state, cmd: AppendString) ⇒
      Effect.persist(AddedStringEvent(cmd.message))
        .thenRun(state ⇒ cmd.replyTo ! Done)

    case (ctx, state, cmd: RemoveString) ⇒
      Effect.persist(RemovedStringEvent(cmd.message))
        .thenRun(state ⇒ cmd.replyTo ! Done)
    case (ctx, state, cmd: ClearMessages) ⇒
      Effect.persist(ClearedEvent)
        .thenRun(state ⇒ cmd.replyTo ! Done)

    case (ctx, state, cmd: GetStrings) ⇒
      cmd.replyTo ! state.messages
      Effect.none

    case (ctx, state, cmd: CauseGrief) ⇒
      throw new Exception("Throwing exception as commanded: " + cmd.message)
  }

  private def eventHandler(persistentId: String): ReliableSideEffectBehaviors.EventHandler[State, Event, Notification] = {
    case (state, AddedStringEvent(message)) ⇒
      state.copy(messages = state.messages :+ message) -> (AddedString(persistentId, message) :: Nil)
    case (state, RemovedStringEvent(message)) ⇒
      state.copy(messages = state.messages.filterNot(_ == message)) -> (RemovedString(persistentId, message) :: Nil)
    case (state, ClearedEvent) ⇒
      State.empty -> state.messages.map(RemovedString(persistentId, _))
  }

  def startRseb(persistentId: String)(notificationHandler: (State, Notification) ⇒ Future[Unit]): ReliableSecondaryEffectBehavior[Protocol, Event, State, Notification] =
    ReliableSideEffectBehaviors.receive(
      persistentId,
      State.empty,
      commandHandler,
      eventHandler(persistentId: String),
      notificationHandler)

  /* FIXME: we have not provided the Notification generation */

  sealed trait ReceptacleProtocol
  object ReceptacleProtocol {
    final case class Add(message: String) extends ReceptacleProtocol
    final case class Clear()(val replyTo: ActorRef[Done]) extends ReceptacleProtocol
    final case class Get()(val replyTo: ActorRef[im.Seq[String]]) extends ReceptacleProtocol
  }

  def startReceptacle: Behavior[ReceptacleProtocol] = {
    def next(content: Vector[String]): Behavior[ReceptacleProtocol] = Behaviors.receiveMessage[ReceptacleProtocol] {
      case cmd: ReceptacleProtocol.Add ⇒
        next(content :+ cmd.message)
      case cmd: ReceptacleProtocol.Clear ⇒
        cmd.replyTo ! Done
        next(Vector.empty)
      case q: ReceptacleProtocol.Get ⇒
        q.replyTo ! content
        Behaviors.same
    }

    next(Vector.empty)
  }
}

class ReliableSideEffectBehaviorSpec extends AsyncFlatSpec with Matchers with BeforeAndAfterAll with ActorTestKit {
  override protected def afterAll(): Unit = {
    shutdownTestKit()
  }

  import ReliableSideEffectBehaviorSpec._

  override def config: Config = ConfigFactory.parseString(
    s"""
    akka.actor.default-dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor {
        fixed-pool-size = 5
      }
    }
    akka.persistence.max-concurrent-recoveries = 3
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.actor.warn-about-java-serializer-usage = off
  """)

  implicit override def executionContext: ExecutionContext = system.executionContext

  "a ReliableSideEffectBehaviorSpec" should "demonstrate proper basic operation even without notifications" in {

    var counter: Int = 0

    def startActor = {
      counter = counter + 1
      system.systemActorOf(
        startRseb("abc") { (state, nfy) ⇒ Future.successful(()) },
        s"abcActor-${counter}")
    }

    val fStage1 = for {
      rsebActor ← startActor
      initial ← rsebActor ? GetStrings()
      _ = initial shouldBe empty

      _ ← rsebActor ? AppendString("abc")
      _ ← rsebActor ? AppendString("def")
      _ ← rsebActor ? AppendString("ghi")
      _ ← rsebActor ? AppendString("klm")
      _ ← rsebActor ? RemoveString("def")

      result ← rsebActor ? GetStrings()

      _ ← rsebActor ? Stop()

    } yield {
      result shouldEqual Vector("abc", "ghi", "klm")
    }

    val fStage2 = for {
      _ ← fStage1
      rsebActor ← startActor
      result ← rsebActor ? GetStrings()
    } yield {
      result shouldEqual Vector("abc", "ghi", "klm")
    }

    fStage2
    //Await.result(fStage2, implicitly[Timeout].duration)
  }

  it should "demonstrate proper notification delivery (in the absence of trouble)" in {

    val fReceptacle = system.systemActorOf(startReceptacle, "receptacle")
    val receptacle = Await.result(fReceptacle, implicitly[Timeout].duration)

    var counter: Int = 0

    def startActor = {
      counter = counter + 1
      system.systemActorOf(
        startRseb("def") {
        case (state, nfy: AddedString) ⇒ Future {
          receptacle ! ReceptacleProtocol.Add("+" + nfy.message)
        }
        case (state, nfy: RemovedString) ⇒ Future {
          receptacle ! ReceptacleProtocol.Add("-" + nfy.message)
        }
      },
        s"defActor-${counter}")
    }

    val fStage1 = for {
      rsebActor ← startActor
      initial ← rsebActor ? GetStrings()
      _ = initial shouldBe empty

      _ ← rsebActor ? AppendString("abc")
      _ ← rsebActor ? AppendString("def")
      _ ← rsebActor ? AppendString("ghi")
      _ ← rsebActor ? AppendString("klm")
      _ ← rsebActor ? RemoveString("def")

      result ← rsebActor ? GetStrings()

      _ ← rsebActor ? Stop()

      receivedNotifications ← receptacle ? ReceptacleProtocol.Get()

    } yield {
      result shouldEqual Vector("abc", "ghi", "klm")
      receivedNotifications.to[Vector].sorted.mkString("\n") shouldEqual
        """+abc
          |+def
          |+ghi
          |+klm
          |-def""".stripMargin
      // note: we must sort the received notifications, as there is no reason that the notifications happen synchronously.
    }

    val fStage2 = for {
      _ ← fStage1
      rsebActor ← startActor
      result ← rsebActor ? GetStrings()

      receivedNotifications ← receptacle ? ReceptacleProtocol.Get()

      _ ← rsebActor ? Stop()
    } yield {
      result shouldEqual Vector("abc", "ghi", "klm")

      receivedNotifications.to[Vector].sorted.mkString("\n") shouldEqual
        """+abc
          |+def
          |+ghi
          |+klm
          |-def""".stripMargin
    }

    val fStage3 = for {
      _ ← fStage2
      rsebActor ← startActor

      _ ← rsebActor ? ClearMessages()
      _ ← rsebActor ? AppendString("ABC")
      _ ← rsebActor ? AppendString("DEF")
      _ ← rsebActor ? AppendString("GHI")

      result ← rsebActor ? GetStrings()
      _ ← rsebActor ? Stop()

      receivedNotifications ← receptacle ? ReceptacleProtocol.Get()
    } yield {
      result shouldEqual Vector("ABC", "DEF", "GHI")

      receivedNotifications.to[Vector].sorted.mkString("\n") shouldEqual
        """+ABC
          |+DEF
          |+GHI
          |+abc
          |+def
          |+ghi
          |+klm
          |-abc
          |-def
          |-ghi
          |-klm""".stripMargin
    }

    val fStage4 = for {
      _ ← fStage3
      _ ← receptacle ? ReceptacleProtocol.Clear()
      cleared ← receptacle ? ReceptacleProtocol.Get()
      _ = cleared shouldBe empty

      rsebActor ← startActor

      result ← rsebActor ? GetStrings()
      _ ← rsebActor ? Stop()

      receivedNotifications ← receptacle ? ReceptacleProtocol.Get()
    } yield {
      result shouldEqual Vector("ABC", "DEF", "GHI")

      println(system.printTree)

      /* since we've done a stop/replay cycle where the notification delivery machinery was more or less at rest,
      * we should not reissue the notifications (regardless of snapshots) */
      receivedNotifications.to[Vector].sorted.mkString("\n") shouldEqual
        """""".stripMargin
    }

    //Await.result(fStage4, implicitly[Timeout].duration)
    fStage4
  }

  /* TODO: now do test we successfully recover from a glitch during a notification */

  it should "demonstrate proper notification delivery even if the actor dies & is restarted" in {

    val fReceptacle = system.systemActorOf(startReceptacle, "receptacleGhi")

    var counter: Int = 0

    def startActor(receptacle: ActorRef[ReceptacleProtocol]) = {
      counter = counter + 1

      val bhv: Behavior[Protocol] = startRseb("ghi") {
        case (state, nfy: AddedString) ⇒ Future {
          receptacle ! ReceptacleProtocol.Add("+" + nfy.message)
        }
        case (state, nfy: RemovedString) ⇒ Future {
          receptacle ! ReceptacleProtocol.Add("-" + nfy.message)
        }
      }
      val supervised = Behaviors.supervise(bhv)
        .onFailure(SupervisorStrategy.restart) // this is important

      system.systemActorOf(
        supervised,
        s"ghiActor-${counter}")

    }

    val fStage1 = for {
      receptacle ← fReceptacle
      rsebActor ← startActor(receptacle)
      initial ← rsebActor ? GetStrings()
      _ = initial shouldBe empty

      _ ← rsebActor ? AppendString("abc")
      _ ← rsebActor ? AppendString("def")

      _ = rsebActor ! CauseGrief("boo!") // this throws an exception in the command handler, and causes the actor to die and be respawned.

      /* let the supervision take notice and retry: */
      _ = try {
        Await.result(rsebActor ? GetStrings(), 3.seconds)
        Unit
      } catch {
        case ex: TimeoutException ⇒
          Unit
      }
      /* and now we should be happy bunnies : */

      _ ← rsebActor ? AppendString("ghi")
      _ ← rsebActor ? AppendString("klm")
      _ ← rsebActor ? RemoveString("def")

      result ← rsebActor ? GetStrings()

      _ ← rsebActor ? Stop()

      receivedNotifications ← receptacle ? ReceptacleProtocol.Get()

    } yield {
      println(system.printTree)

      result shouldEqual Vector("abc", "ghi", "klm")
      /*
        The point is that IT DOES NOT MATTER that we got trouble in the middle, supervision caused a recovery
        and we successfully restarted with the same state as in the first tests.

        Notification testing may be more tricky: we will have or not have
       */

      val notificationsCounts = receivedNotifications.groupBy(Predef.identity).mapValues(_.size)

      notificationsCounts.keys.to[Vector].sorted.mkString("\n") shouldEqual
        """+abc
          |+def
          |+ghi
          |+klm
          |-def""".stripMargin

      // pre-crash notifications may be replayed (this is time-sensitive)
      notificationsCounts("+abc") should be >= 1
      notificationsCounts("+def") should be >= 1
      // post-crash notifications shouldd be delivered only once
      notificationsCounts("+ghi") shouldEqual 1
      notificationsCounts("+klm") shouldEqual 1
      notificationsCounts("-def") shouldEqual 1
    }

    fStage1

  }
}
