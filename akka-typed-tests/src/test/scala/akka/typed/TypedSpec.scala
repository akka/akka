/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import org.scalatest.refspec.RefSpec
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll
import akka.testkit.AkkaSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.util.Timeout

import scala.reflect.ClassTag
import akka.actor.ActorInitializationException

import language.existentials
import akka.testkit.TestEvent.Mute
import akka.typed.scaladsl.Actor._
import org.scalatest.concurrent.ScalaFutures
import org.scalactic.TypeCheckedTripleEquals
import org.scalactic.CanEqual
import org.junit.runner.RunWith

import scala.util.control.NonFatal
import akka.typed.scaladsl.AskPattern

import scala.util.control.NoStackTrace
import akka.typed.testkit.{ Inbox, TestKitSettings }
import org.scalatest.time.Span

/**
 * Helper class for writing tests for typed Actors with ScalaTest.
 */
@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TypedSpecSetup extends RefSpec with Matchers with BeforeAndAfterAll with ScalaFutures with TypeCheckedTripleEquals {

  // TODO hook this up with config like in akka-testkit/AkkaSpec?
  implicit val akkaPatience = PatienceConfig(3.seconds, Span(100, org.scalatest.time.Millis))

}

/**
 * Helper class for writing tests against both ActorSystemImpl and ActorSystemAdapter.
 */
abstract class TypedSpec(val config: Config) extends TypedSpecSetup {
  import TypedSpec._
  import AskPattern._

  def this() = this(ConfigFactory.empty)

  def this(config: String) = this(ConfigFactory.parseString(config))

  // extension point
  def setTimeout: Timeout = Timeout(1.minute)

  private var nativeSystemUsed = false
  lazy val nativeSystem: ActorSystem[TypedSpec.Command] = {
    val sys = ActorSystem(guardian(), AkkaSpec.getCallerName(classOf[TypedSpec]), config = Some(config withFallback AkkaSpec.testConf))
    nativeSystemUsed = true
    sys
  }
  private var adaptedSystemUsed = false
  lazy val adaptedSystem: ActorSystem[TypedSpec.Command] = {
    val sys = ActorSystem.adapter(AkkaSpec.getCallerName(classOf[TypedSpec]), guardian(), config = Some(config withFallback AkkaSpec.testConf))
    adaptedSystemUsed = true
    sys
  }

  trait StartSupport {
    def system: ActorSystem[TypedSpec.Command]

    private val nameCounter = Iterator.from(0)
    def nextName(prefix: String = "a"): String = s"$prefix-${nameCounter.next()}"

    def start[T](behv: Behavior[T]): ActorRef[T] = {
      import akka.typed.scaladsl.AskPattern._
      import akka.typed.testkit.scaladsl._
      implicit val testSettings = TestKitSettings(system)
      Await.result(system ? TypedSpec.Create(behv, nextName()), 3.seconds.dilated)
    }
  }

  trait NativeSystem {
    def system: ActorSystem[TypedSpec.Command] = nativeSystem
  }

  trait AdaptedSystem {
    def system: ActorSystem[TypedSpec.Command] = adaptedSystem
  }

  implicit val timeout = setTimeout
  implicit def scheduler = nativeSystem.scheduler

  override def afterAll(): Unit = {
    if (nativeSystemUsed)
      Await.result(nativeSystem.terminate, timeout.duration)
    if (adaptedSystemUsed)
      Await.result(adaptedSystem.terminate, timeout.duration)
  }

  // TODO remove after basing on ScalaTest 3 with async support
  import akka.testkit._
  def await[T](f: Future[T]): T = Await.result(f, timeout.duration * 1.1)

  lazy val blackhole = await(nativeSystem ? Create(immutable[Any] { case _ ⇒ same }, "blackhole"))

  /**
   * Run an Actor-based test. The test procedure is most conveniently
   * formulated using the [[StepWise$]] behavior type.
   */
  def runTest[T: ClassTag](name: String)(behavior: Behavior[T])(implicit system: ActorSystem[Command]): Future[Status] =
    system ? (RunTest(name, behavior, _, timeout.duration))

  // TODO remove after basing on ScalaTest 3 with async support
  def sync(f: Future[Status])(implicit system: ActorSystem[Command]): Unit = {
    def unwrap(ex: Throwable): Throwable = ex match {
      case ActorInitializationException(_, _, ex) ⇒ ex
      case other                                  ⇒ other
    }

    try await(f) match {
      case Success ⇒ ()
      case Failed(ex) ⇒
        unwrap(ex) match {
          case ex2: TypedSpec.SimulatedException ⇒
            throw ex2
          case _ ⇒
            println(system.printTree)
            throw unwrap(ex)
        }
      case Timedout ⇒
        println(system.printTree)
        fail("test timed out")
    } catch {
      case ex: TypedSpec.SimulatedException ⇒
        throw ex
      case NonFatal(ex) ⇒
        println(system.printTree)
        throw ex
    }
  }

  def muteExpectedException[T <: Exception: ClassTag](
    message:     String = null,
    source:      String = null,
    start:       String = "",
    pattern:     String = null,
    occurrences: Int    = Int.MaxValue)(implicit system: ActorSystem[Command]): EventFilter = {
    val filter = EventFilter(message, source, start, pattern, occurrences)
    system.eventStream.publish(Mute(filter))
    filter
  }

  /**
   * Group assertion that ensures that the given inboxes are empty.
   */
  def assertEmpty(inboxes: Inbox[_]*): Unit = {
    inboxes foreach (i ⇒ withClue(s"inbox $i had messages")(i.hasMessages should be(false)))
  }

  // for ScalaTest === compare of Class objects
  implicit def classEqualityConstraint[A, B]: CanEqual[Class[A], Class[B]] =
    new CanEqual[Class[A], Class[B]] {
      def areEqual(a: Class[A], b: Class[B]) = a == b
    }

  implicit def setEqualityConstraint[A, T <: Set[_ <: A]]: CanEqual[Set[A], T] =
    new CanEqual[Set[A], T] {
      def areEqual(a: Set[A], b: T) = a == b
    }
}

object TypedSpec {
  import akka.{ typed ⇒ t }

  sealed abstract class Start
  case object Start extends Start

  sealed trait Command
  case class RunTest[T](name: String, behavior: Behavior[T], replyTo: ActorRef[Status], timeout: FiniteDuration) extends Command
  case class Terminate(reply: ActorRef[Status]) extends Command
  case class Create[T](behavior: Behavior[T], name: String)(val replyTo: ActorRef[ActorRef[T]]) extends Command

  sealed trait Status
  case object Success extends Status
  case class Failed(thr: Throwable) extends Status
  case object Timedout extends Status

  class SimulatedException(message: String) extends RuntimeException(message) with NoStackTrace

  def guardian(outstanding: Map[ActorRef[_], ActorRef[Status]] = Map.empty): Behavior[Command] =
    immutable[Command] {
      case (ctx, r: RunTest[t]) ⇒
        val test = ctx.spawn(r.behavior, r.name)
        ctx.schedule(r.timeout, r.replyTo, Timedout)
        ctx.watch(test)
        guardian(outstanding + ((test, r.replyTo)))
      case (_, Terminate(reply)) ⇒
        reply ! Success
        stopped
      case (ctx, c: Create[t]) ⇒
        c.replyTo ! ctx.spawn(c.behavior, c.name)
        same
    } onSignal {
      case (ctx, t @ Terminated(test)) ⇒
        outstanding get test match {
          case Some(reply) ⇒
            if (t.failure eq null) reply ! Success
            else reply ! Failed(t.failure)
            guardian(outstanding - test)
          case None ⇒ same
        }
      case _ ⇒ same
    }

  def getCallerName(clazz: Class[_]): String = {
    val s = (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*TypedSpec.?$)")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }
}

class TypedSpecSpec extends TypedSpec {

  object `A TypedSpec` {

    trait CommonTests {
      implicit def system: ActorSystem[TypedSpec.Command]

      def `must report failures`(): Unit = {
        a[TypedSpec.SimulatedException] must be thrownBy {
          sync(runTest("failure")(StepWise[String]((ctx, startWith) ⇒
            startWith {
              throw new TypedSpec.SimulatedException("expected")
            })))
        }
      }
    }

    object `when using the native implementation` extends CommonTests with NativeSystem
    object `when using the adapted implementation` extends CommonTests with AdaptedSystem
  }
}
