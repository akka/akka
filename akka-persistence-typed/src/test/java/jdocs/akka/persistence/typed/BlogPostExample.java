/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;

public interface BlogPostExample {

  // #event
  interface BlogEvent {}

  public class PostAdded implements BlogEvent {
    private final String postId;
    private final PostContent content;

    public PostAdded(String postId, PostContent content) {
      this.postId = postId;
      this.content = content;
    }
  }

  public class BodyChanged implements BlogEvent {
    private final String postId;
    private final String newBody;

    public BodyChanged(String postId, String newBody) {
      this.postId = postId;
      this.newBody = newBody;
    }
  }

  public class Published implements BlogEvent {
    private final String postId;

    public Published(String postId) {
      this.postId = postId;
    }
  }
  // #event

  // #state
  interface BlogState {}

  public enum BlankState implements BlogState {
    INSTANCE
  }

  public class DraftState implements BlogState {
    final PostContent content;

    DraftState(PostContent content) {
      this.content = content;
    }

    public DraftState withContent(PostContent newContent) {
      return new DraftState(newContent);
    }

    public DraftState withBody(String newBody) {
      return withContent(new PostContent(postId(), content.title, newBody));
    }

    public String postId() {
      return content.postId;
    }
  }

  public class PublishedState implements BlogState {
    final PostContent content;

    PublishedState(PostContent content) {
      this.content = content;
    }

    public PublishedState withContent(PostContent newContent) {
      return new PublishedState(newContent);
    }

    public PublishedState withBody(String newBody) {
      return withContent(new PostContent(postId(), content.title, newBody));
    }

    public String postId() {
      return content.postId;
    }
  }
  // #state

  // #commands
  public interface BlogCommand {}
  // #reply-command
  public class AddPost implements BlogCommand {
    final PostContent content;
    final ActorRef<AddPostDone> replyTo;

    public AddPost(PostContent content, ActorRef<AddPostDone> replyTo) {
      this.content = content;
      this.replyTo = replyTo;
    }
  }

  public class AddPostDone implements BlogCommand {
    final String postId;

    public AddPostDone(String postId) {
      this.postId = postId;
    }
  }
  // #reply-command
  public class GetPost implements BlogCommand {
    final ActorRef<PostContent> replyTo;

    public GetPost(ActorRef<PostContent> replyTo) {
      this.replyTo = replyTo;
    }
  }

  public class ChangeBody implements BlogCommand {
    final String newBody;
    final ActorRef<Done> replyTo;

    public ChangeBody(String newBody, ActorRef<Done> replyTo) {
      this.newBody = newBody;
      this.replyTo = replyTo;
    }
  }

  public class Publish implements BlogCommand {
    final ActorRef<Done> replyTo;

    public Publish(ActorRef<Done> replyTo) {
      this.replyTo = replyTo;
    }
  }

  public class PostContent implements BlogCommand {
    final String postId;
    final String title;
    final String body;

    public PostContent(String postId, String title, String body) {
      this.postId = postId;
      this.title = title;
      this.body = body;
    }
  }
  // #commands

  // #behavior
  public class BlogBehavior extends EventSourcedBehavior<BlogCommand, BlogEvent, BlogState> {

    public BlogBehavior(PersistenceId persistenceId) {
      super(persistenceId);
    }

    // #behavior

    // #command-handler
    @Override
    public CommandHandler<BlogCommand, BlogEvent, BlogState> commandHandler() {
      CommandHandlerBuilder<BlogCommand, BlogEvent, BlogState> builder = newCommandHandlerBuilder();

      builder.forStateType(BlankState.class).onCommand(AddPost.class, this::addPost);

      builder
          .forStateType(DraftState.class)
          .onCommand(ChangeBody.class, this::changeBody)
          .onCommand(Publish.class, this::publish)
          .onCommand(GetPost.class, this::getPost);

      builder
          .forStateType(PublishedState.class)
          .onCommand(ChangeBody.class, this::changeBody)
          .onCommand(GetPost.class, this::getPost);

      builder.forAnyState().onCommand(AddPost.class, (state, cmd) -> Effect().unhandled());

      return builder.build();
    }

    private Effect<BlogEvent, BlogState> addPost(AddPost cmd) {
      // #reply
      PostAdded event = new PostAdded(cmd.content.postId, cmd.content);
      return Effect()
          .persist(event)
          .thenRun(() -> cmd.replyTo.tell(new AddPostDone(cmd.content.postId)));
      // #reply
    }

    private Effect<BlogEvent, BlogState> changeBody(DraftState state, ChangeBody cmd) {
      BodyChanged event = new BodyChanged(state.postId(), cmd.newBody);
      return Effect().persist(event).thenRun(() -> cmd.replyTo.tell(Done.getInstance()));
    }

    private Effect<BlogEvent, BlogState> changeBody(PublishedState state, ChangeBody cmd) {
      BodyChanged event = new BodyChanged(state.postId(), cmd.newBody);
      return Effect().persist(event).thenRun(() -> cmd.replyTo.tell(Done.getInstance()));
    }

    private Effect<BlogEvent, BlogState> publish(DraftState state, Publish cmd) {
      return Effect()
          .persist(new Published(state.postId()))
          .thenRun(
              () -> {
                System.out.println("Blog post published: " + state.postId());
                cmd.replyTo.tell(Done.getInstance());
              });
    }

    private Effect<BlogEvent, BlogState> getPost(DraftState state, GetPost cmd) {
      cmd.replyTo.tell(state.content);
      return Effect().none();
    }

    private Effect<BlogEvent, BlogState> getPost(PublishedState state, GetPost cmd) {
      cmd.replyTo.tell(state.content);
      return Effect().none();
    }
    // #command-handler

    // #event-handler
    @Override
    public EventHandler<BlogState, BlogEvent> eventHandler() {

      EventHandlerBuilder<BlogState, BlogEvent> builder = newEventHandlerBuilder();

      builder
          .forStateType(BlankState.class)
          .onEvent(PostAdded.class, event -> new DraftState(event.content));

      builder
          .forStateType(DraftState.class)
          .onEvent(BodyChanged.class, (state, chg) -> state.withBody(chg.newBody))
          .onEvent(Published.class, (state, event) -> new PublishedState(state.content));

      builder
          .forStateType(PublishedState.class)
          .onEvent(BodyChanged.class, (state, chg) -> state.withBody(chg.newBody));

      return builder.build();
    }
    // #event-handler

    // #behavior
    public static Behavior<BlogCommand> behavior(String entityId) {
      return Behaviors.setup(ctx -> new BlogBehavior(new PersistenceId("Blog-" + entityId)));
    }

    @Override
    public BlogState emptyState() {
      return BlankState.INSTANCE;
    }

    // commandHandler, eventHandler as in above snippets
  }
  // #behavior
}
