/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed.aa

import akka.Done
import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.ActorRef
import akka.persistence.typed.scaladsl._
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Span }
import org.scalatest.wordspec.AnyWordSpecLike

object AABlogExampleSpec {
  val config = ConfigFactory.parseString("""
       akka.actor.provider = cluster
       akka.loglevel = debug
       akka.persistence {
        journal {
          plugin = "akka.persistence.journal.inmem"
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

  sealed trait BlogEvent
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

  def behavior(ctx: ActiveActiveContext) =
    EventSourcedBehavior[BlogCommand, BlogEvent, BlogState](
      ctx.persistenceId,
      emptyState,
      (state, cmd) =>
        cmd match {
          case AddPost(_, content, replyTo) =>
            val evt =
              PostAdded(ctx.persistenceId.id, content, state.contentTimestamp.increase(ctx.timestamp, ctx.replicaId))
            Effect.persist(evt).thenRun { _ =>
              replyTo ! AddPostDone(ctx.id)
            }
          case ChangeBody(_, newContent, replyTo) =>
            val evt =
              BodyChanged(
                ctx.persistenceId.id,
                newContent,
                state.contentTimestamp.increase(ctx.timestamp, ctx.replicaId))
            Effect.persist(evt).thenRun { _ =>
              replyTo ! Done
            }
          case p: Publish =>
            Effect.persist(Published("id")).thenRun { _ =>
              p.replyTo ! Done
            }
          case gp: GetPost =>
            state.content.foreach(content => gp.replyTo ! content)
            Effect.none
        },
      (state, event) =>
        event match {
          case PostAdded(_, content, timestamp) =>
            if (timestamp.isAfter(state.contentTimestamp))
              state.withContent(content, timestamp)
            else state
          case BodyChanged(_, newContent, timestamp) =>
            if (timestamp.isAfter(state.contentTimestamp))
              state.withContent(newContent, timestamp)
            else state
          case Published(_) =>
            state.copy(published = true)
        })

  "Blog Example" should {
    "work" in {
      val defDcA: ActorRef[BlogCommand] =
        spawn(ActiveActiveEventSourcing("cat", "DC-A", Set("DC-A", "DC-B")) { (ctx: ActiveActiveContext) =>
          behavior(ctx)
        })

      val defDcB: ActorRef[BlogCommand] =
        spawn(ActiveActiveEventSourcing("cat", "DC-B", Set("DC-A", "DC-B")) { (ctx: ActiveActiveContext) =>
          behavior(ctx)
        })

      import akka.actor.typed.scaladsl.AskPattern._
      import akka.util.Timeout

      import scala.concurrent.duration._
      implicit val timeout: Timeout = 3.seconds

      val content = PostContent("cats are the bets", "yep")
      val response =
        defDcA.ask[AddPostDone](replyTo => AddPost("cat", content, replyTo)).futureValue

      response shouldEqual AddPostDone("cat")

      eventually {
        defDcA.ask[PostContent](replyTo => GetPost("cat", replyTo)).futureValue shouldEqual content
      }

      // won't pass until we implement it
      eventually {
        defDcB.ask[PostContent](replyTo => GetPost("cat", replyTo)).futureValue shouldEqual content
      }

    }
  }
}
