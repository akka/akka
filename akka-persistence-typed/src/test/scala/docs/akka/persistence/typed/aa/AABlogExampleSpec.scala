/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed.aa

import java.util.UUID

import akka.Done
import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.typed.scaladsl._
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Span }
import org.scalatest.wordspec.AnyWordSpecLike

object AABlogExampleSpec {
  val config =
    ConfigFactory.parseString(s"""
                                            
       akka.actor.allow-java-serialization = true 
       // FIXME serializers for replicated event or akka persistence support for metadata: https://github.com/akka/akka/issues/29260
       akka.actor.provider = cluster
       akka.loglevel = debug
       akka.persistence {
        journal {
        plugin = "akka.persistence.journal.leveldb"
           leveldb {
             native = off
             dir = "target/journal-AABlogExampleSpec-${UUID.randomUUID()}"
           }
         }
       }
      """)

  final case class BlogState(content: Option[PostContent], contentTimestamp: LwwTime, published: Boolean) {
    def withContent(newContent: PostContent, timestamp: LwwTime): BlogState =
      copy(content = Some(newContent), contentTimestamp = timestamp)
    def isEmpty: Boolean = content.isEmpty
  }
  val emptyState: BlogState = BlogState(None, LwwTime(Long.MinValue, ""), published = false)

  final case class PostContent(title: String, body: String)
  final case class PostSummary(postId: String, title: String)
  final case class Published(postId: String) extends BlogEvent

  sealed trait BlogCommand
  final case class AddPost(postId: String, content: PostContent, replyTo: ActorRef[AddPostDone]) extends BlogCommand
  final case class AddPostDone(postId: String)
  final case class GetPost(postId: String, replyTo: ActorRef[PostContent]) extends BlogCommand
  final case class ChangeBody(postId: String, newContent: PostContent, replyTo: ActorRef[Done]) extends BlogCommand
  final case class Publish(postId: String, replyTo: ActorRef[Done]) extends BlogCommand

  sealed trait BlogEvent extends CborSerializable
  final case class PostAdded(postId: String, content: PostContent, timestamp: LwwTime) extends BlogEvent
  final case class BodyChanged(postId: String, newContent: PostContent, timestamp: LwwTime) extends BlogEvent
}

class AABlogExampleSpec
    extends ScalaTestWithActorTestKit(AABlogExampleSpec.config)
    with AnyWordSpecLike
    with Matchers
    with LogCapturing
    with ScalaFutures
    with Eventually {
  import AABlogExampleSpec._

  implicit val config: PatienceConfig = PatienceConfig(timeout = Span(timeout.duration.toMillis, Millis))

  def behavior(aa: ActiveActiveContext, ctx: ActorContext[BlogCommand]) =
    EventSourcedBehavior[BlogCommand, BlogEvent, BlogState](
      aa.persistenceId,
      emptyState,
      (state, cmd) =>
        cmd match {
          case AddPost(_, content, replyTo) =>
            val evt =
              PostAdded(aa.persistenceId.id, content, state.contentTimestamp.increase(aa.timestamp, aa.replicaId))
            Effect.persist(evt).thenRun { _ =>
              replyTo ! AddPostDone(aa.entityId)
            }
          case ChangeBody(_, newContent, replyTo) =>
            val evt =
              BodyChanged(aa.persistenceId.id, newContent, state.contentTimestamp.increase(aa.timestamp, aa.replicaId))
            Effect.persist(evt).thenRun { _ =>
              replyTo ! Done
            }
          case p: Publish =>
            Effect.persist(Published("id")).thenRun { _ =>
              p.replyTo ! Done
            }
          case gp: GetPost =>
            ctx.log.info("GetPost {}", state.content)
            state.content.foreach(content => gp.replyTo ! content)
            Effect.none
        },
      (state, event) => {
        ctx.log.info(s"${aa.entityId}:${aa.replicaId} Received event $event")
        event match {
          case PostAdded(_, content, timestamp) =>
            if (timestamp.isAfter(state.contentTimestamp)) {
              val s = state.withContent(content, timestamp)
              ctx.log.info("Updating content. New content is {}", s)
              s
            } else {
              ctx.log.info("Ignoring event as timestamp is older")
              state
            }
          case BodyChanged(_, newContent, timestamp) =>
            if (timestamp.isAfter(state.contentTimestamp))
              state.withContent(newContent, timestamp)
            else state
          case Published(_) =>
            state.copy(published = true)
        }
      })

  "Blog Example" should {
    "work" in {
      val refDcA: ActorRef[BlogCommand] =
        spawn(Behaviors.setup[BlogCommand] { ctx =>
          ActiveActiveEventSourcing("cat", "DC-A", Set("DC-A", "DC-B"), LeveldbReadJournal.Identifier) {
            (aa: ActiveActiveContext) =>
              behavior(aa, ctx)
          }
        }, "dc-a")

      val refDcB: ActorRef[BlogCommand] =
        spawn(Behaviors.setup[BlogCommand] { ctx =>
          ActiveActiveEventSourcing("cat", "DC-B", Set("DC-A", "DC-B"), LeveldbReadJournal.Identifier) {
            (aa: ActiveActiveContext) =>
              behavior(aa, ctx)
          }
        }, "dc-b")

      import akka.actor.typed.scaladsl.AskPattern._
      import akka.util.Timeout

      import scala.concurrent.duration._
      implicit val timeout: Timeout = 3.seconds

      val content = PostContent("cats are the bets", "yep")
      val response =
        refDcA.ask[AddPostDone](replyTo => AddPost("cat", content, replyTo)).futureValue

      response shouldEqual AddPostDone("cat")

      eventually {
        refDcA.ask[PostContent](replyTo => GetPost("cat", replyTo)).futureValue shouldEqual content
      }

      eventually {
        refDcB.ask[PostContent](replyTo => GetPost("cat", replyTo)).futureValue shouldEqual content
      }
    }
  }
}
