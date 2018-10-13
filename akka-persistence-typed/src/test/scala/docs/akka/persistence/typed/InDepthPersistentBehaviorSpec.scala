/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.Done
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.PersistentBehavior
import akka.persistence.typed.scaladsl.Effect

object InDepthPersistentBehaviorSpec {

  //#event
  sealed trait BlogEvent extends Serializable
  final case class PostAdded(
    postId:  String,
    content: PostContent) extends BlogEvent

  final case class BodyChanged(
    postId:  String,
    newBody: String) extends BlogEvent
  final case class Published(postId: String) extends BlogEvent
  //#event

  //#state
  object BlogState {
    val empty = BlogState(None, published = false)
  }

  final case class BlogState(content: Option[PostContent], published: Boolean) {
    def withContent(newContent: PostContent): BlogState =
      copy(content = Some(newContent))
    def isEmpty: Boolean = content.isEmpty
    def postId: String = content match {
      case Some(c) ⇒ c.postId
      case None    ⇒ throw new IllegalStateException("postId unknown before post is created")
    }
  }
  //#state

  //#commands
  sealed trait BlogCommand extends Serializable
  final case class AddPost(content: PostContent, replyTo: ActorRef[AddPostDone]) extends BlogCommand
  final case class AddPostDone(postId: String)
  final case class GetPost(replyTo: ActorRef[PostContent]) extends BlogCommand
  final case class ChangeBody(newBody: String, replyTo: ActorRef[Done]) extends BlogCommand
  final case class Publish(replyTo: ActorRef[Done]) extends BlogCommand
  final case object PassivatePost extends BlogCommand
  final case class PostContent(postId: String, title: String, body: String)
  //#commands

  //#initial-command-handler
  private val initial: (BlogState, BlogCommand) ⇒ Effect[BlogEvent, BlogState] =
    (state, cmd) ⇒
      cmd match {
        case AddPost(content, replyTo) ⇒
          val evt = PostAdded(content.postId, content)
          Effect.persist(evt).thenRun { state2 ⇒
            // After persist is done additional side effects can be performed
            replyTo ! AddPostDone(content.postId)
          }
        case PassivatePost ⇒
          Effect.stop
        case _ ⇒
          Effect.unhandled
      }
  //#initial-command-handler

  //#post-added-command-handler
  private val postAdded: (BlogState, BlogCommand) ⇒ Effect[BlogEvent, BlogState] = {
    (state, cmd) ⇒
      cmd match {
        case ChangeBody(newBody, replyTo) ⇒
          val evt = BodyChanged(state.postId, newBody)
          Effect.persist(evt).thenRun { _ ⇒
            replyTo ! Done
          }
        case Publish(replyTo) ⇒
          Effect.persist(Published(state.postId)).thenRun { _ ⇒
            println(s"Blog post ${state.postId} was published")
            replyTo ! Done
          }
        case GetPost(replyTo) ⇒
          replyTo ! state.content.get
          Effect.none
        case _: AddPost ⇒
          Effect.unhandled
        case PassivatePost ⇒
          Effect.stop
      }
  }
  //#post-added-command-handler

  //#by-state-command-handler
  private val commandHandler: (BlogState, BlogCommand) ⇒ Effect[BlogEvent, BlogState] = { (state, command) ⇒
    if (state.isEmpty) initial(state, command)
    else postAdded(state, command)
  }
  //#by-state-command-handler

  //#event-handler
  private val eventHandler: (BlogState, BlogEvent) ⇒ BlogState = { (state, event) ⇒
    event match {
      case PostAdded(postId, content) ⇒
        state.withContent(content)

      case BodyChanged(_, newBody) ⇒
        state.content match {
          case Some(c) ⇒ state.copy(content = Some(c.copy(body = newBody)))
          case None    ⇒ state
        }

      case Published(_) ⇒
        state.copy(published = true)
    }
  }
  //#event-handler

  //#behavior
  def behavior(entityId: String): Behavior[BlogCommand] =
    PersistentBehavior[BlogCommand, BlogEvent, BlogState](
      persistenceId = "Blog-" + entityId,
      emptyState = BlogState.empty,
      commandHandler,
      eventHandler)
  //#behavior
}

