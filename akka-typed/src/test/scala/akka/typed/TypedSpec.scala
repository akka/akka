/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.typed

import org.scalatest.Spec
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
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute

/**
 * Helper class for writing tests for typed Actors with ScalaTest.
 */
class TypedSpec(config: Config) extends Spec with Matchers with BeforeAndAfterAll {
  import TypedSpec._
  import AskPattern._

  def this() = this(ConfigFactory.empty)

  implicit val system = ActorSystem(AkkaSpec.getCallerName(classOf[TypedSpec]), Props(guardian()), Some(config withFallback AkkaSpec.testConf))

  implicit def timeout = Timeout(1.minute)

  override def afterAll(): Unit = {
    Await.result(system ? (Terminate(_)), timeout.duration): Status
  }

  // TODO remove after basing on ScalaTest 3 with async support
  def await[T](f: Future[T]): T = Await.result(f, 30.seconds)

  val blackhole = await(system ? Create(Props(ScalaDSL.Full[Any] { case _ ⇒ ScalaDSL.Same }), "blackhole"))

  /**
   * Run an Actor-based test. The test procedure is most conveniently
   * formulated using the [[StepWise$]] behavior type.
   */
  def runTest[T: ClassTag](name: String)(behavior: Behavior[T]): Future[Status] =
    system ? (RunTest(name, Props(behavior), _, timeout.duration))

  // TODO remove after basing on ScalaTest 3 with async support
  def sync(f: Future[Status]): Unit = {
    def unwrap(ex: Throwable): Throwable = ex match {
      case ActorInitializationException(_, _, ex) ⇒ ex
      case other                                  ⇒ other
    }

    await(f) match {
      case Success    ⇒ ()
      case Failed(ex) ⇒ throw unwrap(ex)
      case Timedout   ⇒ fail("test timed out")
    }
  }

  def muteExpectedException[T <: Exception: ClassTag](
    message: String = null,
    source: String = null,
    start: String = "",
    pattern: String = null,
    occurrences: Int = Int.MaxValue): EventFilter = {
    val filter = EventFilter(message, source, start, pattern, occurrences)
    system.eventStream.publish(Mute(filter))
    filter
  }

  /**
   * Group assertion that ensures that the given inboxes are empty.
   */
  def assertEmpty(inboxes: Inbox.SyncInbox[_]*): Unit = {
    inboxes foreach (i ⇒ withClue(s"inbox $i had messages")(i.hasMessages should be(false)))
  }
}

object TypedSpec {
  import ScalaDSL._
  import akka.{ typed ⇒ t }

  sealed abstract class Start
  case object Start extends Start

  sealed trait Command
  case class RunTest[T](name: String, props: Props[T], replyTo: ActorRef[Status], timeout: FiniteDuration) extends Command
  case class Terminate(reply: ActorRef[Status]) extends Command
  case class Create[T](props: Props[T], name: String)(val replyTo: ActorRef[ActorRef[T]]) extends Command

  sealed trait Status
  case object Success extends Status
  case class Failed(thr: Throwable) extends Status
  case object Timedout extends Status

  def guardian(outstanding: Map[ActorRef[_], ActorRef[Status]] = Map.empty): Behavior[Command] =
    FullTotal {
      case Sig(ctx, f @ t.Failed(ex, test)) ⇒
        outstanding get test match {
          case Some(reply) ⇒
            reply ! Failed(ex)
            f.decide(t.Failed.Stop)
            guardian(outstanding - test)
          case None ⇒
            f.decide(t.Failed.Stop)
            Same
        }
      case Sig(ctx, Terminated(test)) ⇒
        outstanding get test match {
          case Some(reply) ⇒
            reply ! Success
            guardian(outstanding - test)
          case None ⇒ Same
        }
      case _: Sig[_] ⇒ Same
      case Msg(ctx, r: RunTest[t]) ⇒
        val test = ctx.spawn(r.props, r.name)
        ctx.schedule(r.timeout, r.replyTo, Timedout)
        ctx.watch(test)
        guardian(outstanding + ((test, r.replyTo)))
      case Msg(_, Terminate(reply)) ⇒
        reply ! Success
        Stopped
      case Msg(ctx, c: Create[t]) ⇒
        c.replyTo ! ctx.spawn(c.props, c.name)
        Same
    }
}
