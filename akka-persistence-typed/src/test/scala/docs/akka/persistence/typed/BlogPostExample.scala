/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior

object BlogPostExample {

  //#event
  sealed trait BlogEvent
  final case class PostAdded(postId: String, content: PostContent) extends BlogEvent

  final case class BodyChanged(postId: String, newBody: String) extends BlogEvent
  final case class Published(postId: String) extends BlogEvent
  //#event

  //#state
  sealed trait BlogState

  case object BlankState extends BlogState

  final case class DraftState(content: PostContent) extends BlogState {
    def withBody(newBody: String): DraftState =
      copy(content = content.copy(body = newBody))

    def postId: String = content.postId
  }

  final case class PublishedState(content: PostContent) extends BlogState {
    def postId: String = content.postId
  }
  //#state

  //#commands
  sealed trait BlogCommand
  //#reply-command
  final case class AddPost(content: PostContent, replyTo: ActorRef[AddPostDone]) extends BlogCommand
  final case class AddPostDone(postId: String)
  //#reply-command
  final case class GetPost(replyTo: ActorRef[PostContent]) extends BlogCommand
  final case class ChangeBody(newBody: String, replyTo: ActorRef[Done]) extends BlogCommand
  final case class Publish(replyTo: ActorRef[Done]) extends BlogCommand
  final case class PostContent(postId: String, title: String, body: String)
  //#commands

  //#behavior
  def behavior(entityId: String): Behavior[BlogCommand] =
    EventSourcedBehavior[BlogCommand, BlogEvent, BlogState](
      persistenceId = PersistenceId(s"Blog-$entityId"),
      emptyState = BlankState,
      commandHandler,
      eventHandler)
  //#behavior

  //#command-handler
  private val commandHandler: (BlogState, BlogCommand) => Effect[BlogEvent, BlogState] = { (state, command) =>
    state match {

      case BlankState =>
        command match {
          case cmd: AddPost => addPost(cmd)
          case _            => Effect.unhandled
        }

      case draftState: DraftState =>
        command match {
          case cmd: ChangeBody  => changeBody(draftState, cmd)
          case Publish(replyTo) => publish(draftState, replyTo)
          case GetPost(replyTo) => getPost(draftState, replyTo)
          case _: AddPost       => Effect.unhandled
        }

      case publishedState: PublishedState =>
        command match {
          case GetPost(replyTo) => getPost(publishedState, replyTo)
          case _                => Effect.unhandled
        }
    }
  }

  private def addPost(cmd: AddPost): Effect[BlogEvent, BlogState] = {
    //#reply
    val evt = PostAdded(cmd.content.postId, cmd.content)
    Effect.persist(evt).thenRun { _ =>
      // After persist is done additional side effects can be performed
      cmd.replyTo ! AddPostDone(cmd.content.postId)
    }
    //#reply
  }

  private def changeBody(state: DraftState, cmd: ChangeBody): Effect[BlogEvent, BlogState] = {
    val evt = BodyChanged(state.postId, cmd.newBody)
    Effect.persist(evt).thenRun { _ =>
      cmd.replyTo ! Done
    }
  }

  private def publish(state: DraftState, replyTo: ActorRef[Done]): Effect[BlogEvent, BlogState] = {
    Effect.persist(Published(state.postId)).thenRun { _ =>
      println(s"Blog post ${state.postId} was published")
      replyTo ! Done
    }
  }

  private def getPost(state: DraftState, replyTo: ActorRef[PostContent]): Effect[BlogEvent, BlogState] = {
    replyTo ! state.content
    Effect.none
  }

  private def getPost(state: PublishedState, replyTo: ActorRef[PostContent]): Effect[BlogEvent, BlogState] = {
    replyTo ! state.content
    Effect.none
  }
  //#command-handler

  //#event-handler
  private val eventHandler: (BlogState, BlogEvent) => BlogState = { (state, event) =>
    state match {

      case BlankState =>
        event match {
          case PostAdded(_, content) =>
            DraftState(content)
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }

      case draftState: DraftState =>
        event match {

          case BodyChanged(_, newBody) =>
            draftState.withBody(newBody)

          case Published(_) =>
            PublishedState(draftState.content)

          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }

      case _: PublishedState =>
        // no more changes after published
        throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
    }
  }
  //#event-handler

}
