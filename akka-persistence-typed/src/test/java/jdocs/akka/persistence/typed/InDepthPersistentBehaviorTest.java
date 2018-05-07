/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.persistence.typed.javadsl.CommandHandler;
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
  public static class BlogState {
    final Optional<PostContent> postContent;
    final boolean published;

    BlogState(Optional<PostContent> postContent, boolean published) {
      this.postContent = postContent;
      this.published = published;
    }

    public BlogState withContent(PostContent newContent) {
      return new BlogState(Optional.of(newContent), this.published);
    }

    public boolean isEmpty() {
      return postContent.isPresent();
    }

    public String postId() {
      return postContent.orElseGet(() -> {
        throw new IllegalStateException("postId unknown before post is created");
      }).postId;
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

    //#initial-command-handler
    private CommandHandler<BlogCommand, BlogEvent, BlogState> initialCommandHandler = commandHandlerBuilder()
      .matchCommand(AddPost.class, (ctx, state, cmd) -> {
        PostAdded event = new PostAdded(cmd.content.postId, cmd.content);
        return Effect().persist(event)
          .andThen(() -> cmd.replyTo.tell(new AddPostDone(cmd.content.postId)));
      })
      .matchCommand(PassivatePost.class, (ctx, state, cmd) -> Effect().stop())
      .build();
    //#initial-command-handler

    //#post-added-command-handler
    private CommandHandler<BlogCommand, BlogEvent, BlogState> postCommandHandler = commandHandlerBuilder()
      .matchCommand(ChangeBody.class, (ctx, state, cmd) -> {
        BodyChanged event = new BodyChanged(state.postId(), cmd.newBody);
        return Effect().persist(event).andThen(() -> cmd.replyTo.tell(Done.getInstance()));
      })
      .matchCommand(Publish.class, (ctx, state, cmd) -> Effect()
        .persist(new Published(state.postId())).andThen(() -> {
          System.out.println("Blog post published: " + state.postId());
          cmd.replyTo.tell(Done.getInstance());
        }))
      .matchCommand(GetPost.class, (ctx, state, cmd) -> {
        cmd.replyTo.tell(state.postContent.get());
        return Effect().none();
      })
      .matchCommand(AddPost.class, (ctx, state, cmd) -> Effect().unhandled())
      .matchCommand(PassivatePost.class, (ctx, state, cmd) -> Effect().stop())
      .build();
    //#post-added-command-handler


    public BlogBehavior(String persistenceId) {
      super(persistenceId);
    }

    @Override
    public BlogState initialState() {
      return new BlogState(Optional.empty(), false);
    }

    //#by-state-command-handler
    @Override
    public CommandHandler<BlogCommand, BlogEvent, BlogState> commandHandler() {
      return byStateCommandHandlerBuilder()
        .matchState(BlogState.class, (state) -> !state.postContent.isPresent(), initialCommandHandler)
        .matchState(BlogState.class, (state) -> state.postContent.isPresent(), postCommandHandler)
        .build();
    }
    //#by-state-command-handler

    //#event-handler
    @Override
    public EventHandler<BlogEvent, BlogState> eventHandler() {
      return eventHandlerBuilder()
        .matchEvent(PostAdded.class, (state, event) -> state.withContent(event.content))
        .matchEvent(BodyChanged.class, (state, newBody) ->
          new BlogState(state.postContent.map(pc -> new PostContent(pc.postId, pc.title, newBody.newBody)), state.published))
        .matchEvent(Published.class, (state, event) -> new BlogState(state.postContent, true))
        .build();
    }
    //#event-handler
  }
  //#behavior
}
