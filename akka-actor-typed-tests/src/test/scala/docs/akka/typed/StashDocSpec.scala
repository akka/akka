/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.BehaviorTestkit
import akka.testkit.typed.TestInbox
import org.scalatest.Matchers
import org.scalatest.WordSpec

object StashDocSpec {
  // #stashing
  import akka.actor.typed.scaladsl.StashBuffer

  trait DB {
    def save(id: String, value: String): Future[Done]
    def load(id: String): Future[String]
  }

  object DataAccess {
    trait Command
    final case class Save(value: String, replyTo: ActorRef[Done]) extends Command
    final case class Get(replyTo: ActorRef[String]) extends Command
    private final case class InitialState(value: String) extends Command
    private final case object SaveSuccess extends Command
    private final case class DBError(cause: Throwable) extends Command

    def behavior(id: String, db: DB): Behavior[Command] =
      Behaviors.setup[Command] { ctx ⇒

        val buffer = StashBuffer[Command](capacity = 100)

        def init(): Behavior[Command] =
          Behaviors.immutable[Command] { (ctx, msg) ⇒
            msg match {
              case InitialState(value) ⇒
                // now we are ready to handle stashed messages if any
                buffer.unstashAll(ctx, active(value))
              case DBError(cause) ⇒
                throw cause
              case other ⇒
                // stash all other messages for later processing
                buffer.stash(other)
                Behaviors.same
            }
          }

        def active(state: String): Behavior[Command] =
          Behaviors.immutable { (ctx, msg) ⇒
            msg match {
              case Get(replyTo) ⇒
                replyTo ! state
                Behaviors.same
              case Save(value, replyTo) ⇒
                import ctx.executionContext
                db.save(id, value).onComplete {
                  case Success(_)     ⇒ ctx.self ! SaveSuccess
                  case Failure(cause) ⇒ ctx.self ! DBError(cause)
                }
                saving(value, replyTo)
            }
          }

        def saving(state: String, replyTo: ActorRef[Done]): Behavior[Command] =
          Behaviors.immutable[Command] { (ctx, msg) ⇒
            msg match {
              case SaveSuccess ⇒
                replyTo ! Done
                buffer.unstashAll(ctx, active(state))
              case DBError(cause) ⇒
                throw cause
              case other ⇒
                buffer.stash(other)
                Behaviors.same
            }
          }

        import ctx.executionContext
        db.load(id).onComplete {
          case Success(value) ⇒
            ctx.self ! InitialState(value)
          case Failure(cause) ⇒
            ctx.self ! DBError(cause)
        }

        init()
      }
  }
  // #stashing
}

class StashDocSpec extends WordSpec with Matchers {
  import StashDocSpec.DB
  import StashDocSpec.DataAccess

  "Stashing docs" must {

    "illustrate stash and unstashAll" in {

      val db = new DB {
        override def save(id: String, value: String): Future[Done] = Future.successful(Done)
        override def load(id: String): Future[String] = Future.successful("TheValue")
      }
      val testKit = BehaviorTestkit(DataAccess.behavior(id = "17", db))
      val getInbox = TestInbox[String]()
      testKit.run(DataAccess.Get(getInbox.ref))
      val initialStateMsg = testKit.selfInbox().receiveMsg()
      testKit.run(initialStateMsg)
      getInbox.expectMsg("TheValue")

      val saveInbox = TestInbox[Done]()
      testKit.run(DataAccess.Save("UpdatedValue", saveInbox.ref))
      testKit.run(DataAccess.Get(getInbox.ref))
      val saveSuccessMsg = testKit.selfInbox().receiveMsg()
      testKit.run(saveSuccessMsg)
      saveInbox.expectMsg(Done)
      getInbox.expectMsg("UpdatedValue")

      testKit.run(DataAccess.Get(getInbox.ref))
      getInbox.expectMsg("UpdatedValue")

    }
  }
}
