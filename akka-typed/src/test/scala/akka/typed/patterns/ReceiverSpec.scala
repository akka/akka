package akka.typed.patterns

import akka.typed._
import scala.concurrent.duration._
import akka.typed.Effect.{ ReceiveTimeoutSet, Scheduled }
import Receiver._

object ReceiverSpec {
  case class Msg(x: Int)
  case class Setup(name: String, creator: ActorContext[Command[Msg]] ⇒ Behavior[Command[Msg]], messages: Int, effects: Int)
}

class ReceiverSpec extends TypedSpec {
  import ReceiverSpec._

  private val dummyInbox = Inbox.sync[Replies[Msg]]("dummy")

  private val startingPoints: Seq[Setup] = Seq(
    Setup("initial", ctx ⇒ behavior[Msg], 0, 0),
    Setup("afterGetOneFirst", afterGetOneFirst, 1, 0),
    Setup("afterGetOneLater", afterGetOneLater, 1, 2),
    Setup("afterGetOneTimeout", afterGetOneTimeout, 1, 2),
    Setup("afterGetAll", afterGetAll, 1, 1),
    Setup("afterGetAllTimeout", afterGetAllTimeout, 1, 1))

  private def afterGetOneFirst(ctx: ActorContext[Command[Msg]]): Behavior[Command[Msg]] =
    behavior[Msg]
      .asInstanceOf[Behavior[Msg]].message(ctx.asInstanceOf[ActorContext[Msg]], Msg(1)).asInstanceOf[Behavior[Command[Msg]]]
      .message(ctx, GetOne(Duration.Zero)(dummyInbox.ref))

  private def afterGetOneLater(ctx: ActorContext[Command[Msg]]): Behavior[Command[Msg]] =
    behavior[Msg]
      .message(ctx, GetOne(1.second)(dummyInbox.ref))
      .asInstanceOf[Behavior[Msg]].message(ctx.asInstanceOf[ActorContext[Msg]], Msg(1)).asInstanceOf[Behavior[Command[Msg]]]

  private def afterGetOneTimeout(ctx: ActorContext[Command[Msg]]): Behavior[Command[Msg]] =
    behavior[Msg]
      .message(ctx, GetOne(1.nano)(dummyInbox.ref))
      .management(ctx, ReceiveTimeout)

  private def afterGetAll(ctx: ActorContext[Command[Msg]]): Behavior[Command[Msg]] =
    behavior[Msg]
      .message(ctx, GetAll(1.nano)(dummyInbox.ref))
      .asInstanceOf[Behavior[Msg]].message(ctx.asInstanceOf[ActorContext[Msg]], Msg(1)).asInstanceOf[Behavior[Command[Msg]]]
      .message(ctx, GetAll(Duration.Zero)(dummyInbox.ref))

  private def afterGetAllTimeout(ctx: ActorContext[Command[Msg]]): Behavior[Command[Msg]] =
    behavior[Msg]
      .message(ctx, GetAll(1.nano)(dummyInbox.ref))
      .message(ctx, GetAll(Duration.Zero)(dummyInbox.ref))

  private def setup(name: String, behv: Behavior[Command[Msg]] = behavior[Msg])(
    proc: (EffectfulActorContext[Command[Msg]], EffectfulActorContext[Msg], Inbox.SyncInbox[Replies[Msg]]) ⇒ Unit): Unit =
    for (Setup(description, behv, messages, effects) ← startingPoints) {
      val ctx = new EffectfulActorContext("ctx", Props(ScalaDSL.ContextAware(behv)), system)
      withClue(s"[running for starting point '$description' (${ctx.currentBehavior})]: ") {
        dummyInbox.receiveAll() should have size messages
        ctx.getAllEffects() should have size effects
        proc(ctx, ctx.asInstanceOf[EffectfulActorContext[Msg]], Inbox.sync[Replies[Msg]](name))
      }
    }

  object `A Receiver` {

    /*
     * This test suite assumes that the Receiver is only one actor with two
     * sides that share the same ActorRef.
     */
    def `must return "self" as external address`(): Unit =
      setup("") { (int, ext, _) ⇒
        val inbox = Inbox.sync[ActorRef[Msg]]("extAddr")
        int.run(ExternalAddress(inbox.ref))
        int.hasEffects should be(false)
        inbox.receiveAll() should be(List(int.self))
      }

    def `must receive one message which arrived first`(): Unit =
      setup("getOne") { (int, ext, inbox) ⇒
        // first with zero timeout
        ext.run(Msg(1))
        int.run(GetOne(Duration.Zero)(inbox.ref))
        int.getAllEffects() should be(Nil)
        inbox.receiveAll() should be(GetOneResult(int.self, Some(Msg(1))) :: Nil)
        // then with positive timeout
        ext.run(Msg(2))
        int.run(GetOne(1.second)(inbox.ref))
        int.getAllEffects() should be(Nil)
        inbox.receiveAll() should be(GetOneResult(int.self, Some(Msg(2))) :: Nil)
        // then with negative timeout
        ext.run(Msg(3))
        int.run(GetOne(-1.second)(inbox.ref))
        int.getAllEffects() should be(Nil)
        inbox.receiveAll() should be(GetOneResult(int.self, Some(Msg(3))) :: Nil)
      }

    def `must receive one message which arrives later`(): Unit =
      setup("getOneLater") { (int, ext, inbox) ⇒
        int.run(GetOne(1.second)(inbox.ref))
        int.getAllEffects() match {
          case ReceiveTimeoutSet(d) :: Nil ⇒ d > Duration.Zero should be(true)
          case other                       ⇒ fail(s"$other was not List(ReceiveTimeoutSet(_))")
        }
        inbox.hasMessages should be(false)
        ext.run(Msg(1))
        int.getAllEffects() match {
          case ReceiveTimeoutSet(d) :: Nil ⇒ d should be theSameInstanceAs (Duration.Undefined)
        }
        inbox.receiveAll() should be(GetOneResult(int.self, Some(Msg(1))) :: Nil)
      }

    def `must reply with no message when asked for immediate value`(): Unit =
      setup("getNone") { (int, ext, inbox) ⇒
        int.run(GetOne(Duration.Zero)(inbox.ref))
        int.getAllEffects() should be(Nil)
        inbox.receiveAll() should be(GetOneResult(int.self, None) :: Nil)
        int.run(GetOne(-1.second)(inbox.ref))
        int.getAllEffects() should be(Nil)
        inbox.receiveAll() should be(GetOneResult(int.self, None) :: Nil)
      }

    def `must reply with no message after a timeout`(): Unit =
      setup("getNoneTimeout") { (int, ext, inbox) ⇒
        int.run(GetOne(1.nano)(inbox.ref))
        int.getAllEffects() match {
          case ReceiveTimeoutSet(d) :: Nil ⇒ // okay
          case other                       ⇒ fail(s"$other was not List(ReceiveTimeoutSet(_))")
        }
        inbox.hasMessages should be(false)
        // currently this all takes >1ns, but who knows what the future brings
        Thread.sleep(1)
        int.signal(ReceiveTimeout)
        int.getAllEffects() match {
          case ReceiveTimeoutSet(d) :: Nil ⇒ d should be theSameInstanceAs (Duration.Undefined)
          case other                       ⇒ fail(s"$other was not List(ReceiveTimeoutSet(_))")
        }
        inbox.receiveAll() should be(GetOneResult(int.self, None) :: Nil)
      }

    def `must reply with messages which arrived first in the same order (GetOne)`(): Unit =
      setup("getMoreOrderOne") { (int, ext, inbox) ⇒
        ext.run(Msg(1))
        ext.run(Msg(2))
        ext.run(Msg(3))
        ext.run(Msg(4))
        int.run(GetOne(Duration.Zero)(inbox.ref))
        inbox.receiveAll() should be(GetOneResult(int.self, Some(Msg(1))) :: Nil)
        int.run(GetOne(Duration.Zero)(inbox.ref))
        inbox.receiveAll() should be(GetOneResult(int.self, Some(Msg(2))) :: Nil)
        int.run(GetOne(Duration.Zero)(inbox.ref))
        int.run(GetOne(Duration.Zero)(inbox.ref))
        inbox.receiveAll() should be(GetOneResult(int.self, Some(Msg(3))) :: GetOneResult(int.self, Some(Msg(4))) :: Nil)
        int.hasEffects should be(false)
      }

    def `must reply with messages which arrived first in the same order (GetAll)`(): Unit =
      setup("getMoreOrderAll") { (int, ext, inbox) ⇒
        ext.run(Msg(1))
        ext.run(Msg(2))
        int.run(GetAll(Duration.Zero)(inbox.ref))
        inbox.receiveAll() should be(GetAllResult(int.self, List(Msg(1), Msg(2))) :: Nil)
        // now with negative timeout
        ext.run(Msg(3))
        ext.run(Msg(4))
        int.run(GetAll(-1.second)(inbox.ref))
        inbox.receiveAll() should be(GetAllResult(int.self, List(Msg(3), Msg(4))) :: Nil)
        int.hasEffects should be(false)
      }

    private def assertScheduled[T, U](s: Scheduled[T], target: ActorRef[U]): U = {
      s.target should be(target)
      // unfortunately Scala cannot automatically transfer the hereby established type knowledge
      s.msg.asInstanceOf[U]
    }

    def `must reply to GetAll with messages which arrived first`(): Unit =
      setup("getAllFirst") { (int, ext, inbox) ⇒
        ext.run(Msg(1))
        ext.run(Msg(2))
        int.run(GetAll(1.second)(inbox.ref))
        val msg = int.getAllEffects() match {
          case (s: Scheduled[_]) :: Nil ⇒ assertScheduled(s, int.self)
        }
        int.run(msg)
        inbox.receiveAll() should be(GetAllResult(int.self, List(Msg(1), Msg(2))) :: Nil)
        int.hasEffects should be(false)
      }

    def `must reply to GetAll with messages which arrived first and later`(): Unit =
      setup("getAllFirstAndLater") { (int, ext, inbox) ⇒
        ext.run(Msg(1))
        ext.run(Msg(2))
        int.run(GetAll(1.second)(inbox.ref))
        val msg = int.getAllEffects() match {
          case (s: Scheduled[_]) :: Nil ⇒ assertScheduled(s, int.self)
        }
        inbox.hasMessages should be(false)
        ext.run(Msg(3))
        int.run(msg)
        inbox.receiveAll() should be(GetAllResult(int.self, List(Msg(1), Msg(2), Msg(3))) :: Nil)
        int.hasEffects should be(false)
      }

    def `must reply to GetAll with messages which arrived later`(): Unit =
      setup("getAllLater") { (int, ext, inbox) ⇒
        int.run(GetAll(1.second)(inbox.ref))
        val msg = int.getAllEffects() match {
          case (s: Scheduled[_]) :: Nil ⇒ assertScheduled(s, int.self)
        }
        ext.run(Msg(1))
        ext.run(Msg(2))
        inbox.hasMessages should be(false)
        int.run(msg)
        inbox.receiveAll() should be(GetAllResult(int.self, List(Msg(1), Msg(2))) :: Nil)
        int.hasEffects should be(false)
      }

    def `must reply to GetAll immediately while GetOne is pending`(): Unit =
      setup("getAllWhileGetOne") { (int, ext, inbox) ⇒
        int.run(GetOne(1.second)(inbox.ref))
        int.getAllEffects() match {
          case ReceiveTimeoutSet(d) :: Nil ⇒ // okay
          case other                       ⇒ fail(s"$other was not List(ReceiveTimeoutSet(_))")
        }
        inbox.hasMessages should be(false)
        int.run(GetAll(Duration.Zero)(inbox.ref))
        int.getAllEffects() should have size 1
        inbox.receiveAll() should be(GetAllResult(int.self, Nil) :: Nil)
      }

    def `must reply to GetAll later while GetOne is pending`(): Unit =
      setup("getAllWhileGetOne") { (int, ext, inbox) ⇒
        int.run(GetOne(1.second)(inbox.ref))
        int.getAllEffects() match {
          case ReceiveTimeoutSet(d) :: Nil ⇒ // okay
          case other                       ⇒ fail(s"$other was not List(ReceiveTimeoutSet(_))")
        }
        inbox.hasMessages should be(false)
        int.run(GetAll(1.nano)(inbox.ref))
        val msg = int.getAllEffects() match {
          case (s: Scheduled[_]) :: ReceiveTimeoutSet(_) :: Nil ⇒ assertScheduled(s, int.self)
        }
        inbox.receiveAll() should be(Nil)
        int.run(msg)
        inbox.receiveAll() should be(GetAllResult(int.self, Nil) :: Nil)
      }
  }

}
