/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.WordSpecLike

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
      Behaviors.setup[Command] { context ⇒

        val buffer = StashBuffer[Command](capacity = 100)

        def init(): Behavior[Command] =
          Behaviors.receive[Command] { (context, message) ⇒
            message match {
              case InitialState(value) ⇒
                // now we are ready to handle stashed messages if any
                buffer.unstashAll(context, active(value))
              case DBError(cause) ⇒
                throw cause
              case other ⇒
                // stash all other messages for later processing
                buffer.stash(other)
                Behaviors.same
            }
          }

        def active(state: String): Behavior[Command] =
          Behaviors.receive { (context, message) ⇒
            message match {
              case Get(replyTo) ⇒
                replyTo ! state
                Behaviors.same
              case Save(value, replyTo) ⇒
                context.pipeToSelf(db.save(id, value)) {
                  case Success(_)     ⇒ SaveSuccess
                  case Failure(cause) ⇒ DBError(cause)
                }
                saving(value, replyTo)
            }
          }

        def saving(state: String, replyTo: ActorRef[Done]): Behavior[Command] =
          Behaviors.receive[Command] { (context, message) ⇒
            message match {
              case SaveSuccess ⇒
                replyTo ! Done
                buffer.unstashAll(context, active(state))
              case DBError(cause) ⇒
                throw cause
              case other ⇒
                buffer.stash(other)
                Behaviors.same
            }
          }

        context.pipeToSelf(db.load(id)) {
          case Success(value) ⇒ InitialState(value)
          case Failure(cause) ⇒ DBError(cause)
        }

        init()
      }
  }
  // #stashing
}

class StashDocSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import StashDocSpec.DB
  import StashDocSpec.DataAccess

  "Stashing docs" must {

    "illustrate stash and unstashAll" in {

      val db = new DB {
        override def save(id: String, value: String): Future[Done] = Future.successful(Done)
        override def load(id: String): Future[String] = Future.successful("TheValue")
      }
      val dataAccess = spawn(DataAccess.behavior(id = "17", db))
      val getProbe = createTestProbe[String]()
      dataAccess ! DataAccess.Get(getProbe.ref)
      getProbe.expectMessage("TheValue")

      val saveProbe = createTestProbe[Done]()
      dataAccess ! DataAccess.Save("UpdatedValue", saveProbe.ref)
      dataAccess ! DataAccess.Get(getProbe.ref)
      saveProbe.expectMessage(Done)
      getProbe.expectMessage("UpdatedValue")

      dataAccess ! DataAccess.Get(getProbe.ref)
      getProbe.expectMessage("UpdatedValue")
    }
  }
}
