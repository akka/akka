/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.CommandHandlerBuilder;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;

import java.util.Optional;

public class InDepthPersistentBehaviorTest {

  //#event
  interface BlogEvent {
  }
  public static class PostAdded implements BlogEvent {
    private final String postId;
    private final PostContent content;

    public PostAdded(String postId, PostContent content) {
      this.postId = postId;
      this.content = content;
    }
  }

  public static class BodyChanged implements BlogEvent {
    private final String postId;
    private final String newBody;

    public BodyChanged(String postId, String newBody) {
      this.postId = postId;
      this.newBody = newBody;
    }
  }

  public static class Published implements BlogEvent {
    private final String postId;

    public Published(String postId) {
      this.postId = postId;
    }
  }
  //#event

  //#state
  interface BlogState {}

  public static class BlankState implements BlogState {}

  public static class DraftState implements BlogState {
    final PostContent postContent;
    final boolean published;

    DraftState(PostContent postContent, boolean published) {
      this.postContent = postContent;
      this.published = published;
    }

    public DraftState withContent(PostContent newContent) {
      return new DraftState(newContent, this.published);
    }

    public String postId() {
      return postContent.postId;
    }
  }

  public static class PublishedState implements BlogState {
    final PostContent postContent;

    PublishedState(PostContent postContent) {
      this.postContent = postContent;
    }

    public PublishedState withContent(PostContent newContent) {
      return new PublishedState(newContent);
    }

    public String postId() {
      return postContent.postId;
    }
  }
  //#state

  //#commands
  public interface BlogCommand {
  }
  public static class AddPost implements BlogCommand {
    final PostContent content;
    final ActorRef<AddPostDone> replyTo;

    public AddPost(PostContent content, ActorRef<AddPostDone> replyTo) {
      this.content = content;
      this.replyTo = replyTo;
    }
  }
  public static class AddPostDone implements BlogCommand {
    final String postId;

    public AddPostDone(String postId) {
      this.postId = postId;
    }
  }
  public static class GetPost implements BlogCommand {
    final ActorRef<PostContent> replyTo;

    public GetPost(ActorRef<PostContent> replyTo) {
      this.replyTo = replyTo;
    }
  }
  public static class ChangeBody implements BlogCommand {
    final String newBody;
    final ActorRef<Done> replyTo;

    public ChangeBody(String newBody, ActorRef<Done> replyTo) {
      this.newBody = newBody;
      this.replyTo = replyTo;
    }
  }
  public static class Publish implements BlogCommand {
    final ActorRef<Done> replyTo;

    public Publish(ActorRef<Done> replyTo) {
      this.replyTo = replyTo;
    }
  }
  public static class PassivatePost implements BlogCommand {
  }
  public static class PostContent implements BlogCommand {
    final String postId;
    final String title;
    final String body;

    public PostContent(String postId, String title, String body) {
      this.postId = postId;
      this.title = title;
      this.body = body;
    }
  }
  //#commands

  //#behavior
  public static class BlogBehavior extends PersistentBehavior<BlogCommand, BlogEvent, BlogState> {
    //#behavior

    private final ActorContext<BlogCommand> ctx;

    public BlogBehavior(String persistenceId, ActorContext<BlogCommand> ctx) {
      super(persistenceId);
      this.ctx = ctx;
    }

    //#initial-command-handler
    private CommandHandlerBuilder<BlogCommand, BlogEvent, BlankState, BlogState> initialCommandHandler() {
      return commandHandlerBuilder(BlankState.class)
          .matchCommand(AddPost.class, (state, cmd) -> {
            PostAdded event = new PostAdded(cmd.content.postId, cmd.content);
            return Effect().persist(event)
                .andThen(() -> cmd.replyTo.tell(new AddPostDone(cmd.content.postId)));
          });
    }
    //#initial-command-handler

    //#post-added-command-handler
    private CommandHandlerBuilder<BlogCommand, BlogEvent, DraftState, BlogState> draftCommandHandler() {
      return commandHandlerBuilder(DraftState.class)
          .matchCommand(ChangeBody.class, (state, cmd) -> {
            BodyChanged event = new BodyChanged(state.postId(), cmd.newBody);
            return Effect().persist(event).andThen(() -> cmd.replyTo.tell(Done.getInstance()));
          })
          .matchCommand(Publish.class, (state, cmd) -> Effect()
              .persist(new Published(state.postId())).andThen(() -> {
                System.out.println("Blog post published: " + state.postId());
                cmd.replyTo.tell(Done.getInstance());
              }))
          .matchCommand(GetPost.class, (state, cmd) -> {
            cmd.replyTo.tell(state.postContent);
            return Effect().none();
          });
    }

    private CommandHandlerBuilder<BlogCommand, BlogEvent, PublishedState, BlogState> publishedCommandHandler() {
      return commandHandlerBuilder(PublishedState.class)
          .matchCommand(ChangeBody.class, (state, cmd) -> {
            BodyChanged event = new BodyChanged(state.postId(), cmd.newBody);
            return Effect().persist(event).andThen(() -> cmd.replyTo.tell(Done.getInstance()));
          })
          .matchCommand(GetPost.class, (state, cmd) -> {
            cmd.replyTo.tell(state.postContent);
            return Effect().none();
          });
    }

    private CommandHandlerBuilder<BlogCommand, BlogEvent, BlogState, BlogState> commonCommandHandler() {
      return commandHandlerBuilder(BlogState.class)
          .matchCommand(AddPost.class, (state, cmd) -> Effect().unhandled())
          .matchCommand(PassivatePost.class, (state, cmd) -> Effect().stop());
    }
    //#post-added-command-handler


    //#command-handler
    @Override
    public CommandHandler<BlogCommand, BlogEvent, BlogState> commandHandler() {
      return
          initialCommandHandler()
              .orElse(draftCommandHandler())
              .orElse(publishedCommandHandler())
              .orElse(commonCommandHandler())
              .build();
    }
    //#command-handler

    //#event-handler
    @Override
    public EventHandler<BlogState, BlogEvent> eventHandler() {
      return eventHandlerBuilder()
          .matchEvent(PostAdded.class, (state, event) ->
              new DraftState(event.content, false))
          .matchEvent(BodyChanged.class, DraftState.class, (state, chg) ->
              state.withContent(new PostContent(state.postId(), state.postContent.title, chg.newBody)))
          .matchEvent(BodyChanged.class, PublishedState.class, (state, chg) ->
              state.withContent(new PostContent(state.postId(), state.postContent.title, chg.newBody)))
          .matchEvent(Published.class, DraftState.class, (state, event) ->
              new PublishedState(state.postContent))
          .build();
    }
    //#event-handler

    //#behavior
    public static Behavior<BlogCommand> behavior(String entityId) {
      return Behaviors.setup(ctx ->
          new BlogBehavior("Blog-" + entityId, ctx)
      );
    }

    @Override
    public BlogState emptyState() {
      return new BlankState();
    }

    // commandHandler, eventHandler as in above snippets
  }
  //#behavior
}
