# Part 1: Top-level Architecture

In this and the following chapters, we will build a sample Akka application to introduce you to the language of
actors and how solutions can be formulated with them. It is a common hurdle for beginners to translate their project
into actors even though they don't understand what they do on the high-level. We will build the core logic of a small
application and this will serve as a guide for common patterns that will help to kickstart Akka projects.

The application we aim to write will be a simplified IoT system where devices, installed at the home of users, can report temperature data from sensors. Users will be able to query the current state of these sensors. To keep
things simple, we will not actually expose the application via HTTP or any other external API, we will, instead, concentrate only on the
core logic. However, we will write tests for the pieces of the application to get comfortable and
proficient with testing actors early on.

## Our Goals for the IoT System

We will build a simple IoT application with the bare essentials to demonstrate designing an Akka-based system. The application will consist of two main components:

 * **Device data collection:** This component has the responsibility to maintain a local representation of the
   otherwise remote devices. The devices will be organized into device groups, grouping together sensors belonging to a home.
 * **User dashboards:** This component has the responsibility to periodically collect data from the devices for a
   logged in user and present the results as a report.

For simplicity, we will only collect temperature data for the devices, but in a real application our local representations
for a remote device, which we will model as an actor, would have many more responsibilities. Among others; reading the
configuration of the device, changing the configuration, checking if the devices are unresponsive, etc. We leave
these complexities for now as they can be easily added as an exercise.

We will also not address the means by which the remote devices communicate with the local representations (actors). Instead,
we just build an actor based API that such a network protocol could use. We will use tests for our API everywhere though.

The architecture of the application will look like this:

![box diagram of the architecture](diagrams/arch_boxes_diagram.png)

## Top Level Architecture

When writing prose, the hardest part is usually to write the first couple of sentences. There is a similar feeling
when trying to build an Akka system: What should be the first actor? Where should it live? What should it do?
Fortunately, unlike with prose, there are established best practices that can guide us through these initial steps.

When one creates an actor in Akka it always belongs to a certain parent. This means that actors are always organized
into a tree. In general, creating an actor can only happen from inside another actor. This creator actor becomes the
_parent_ of the newly created _child_ actor. You might ask then, who is the parent of the _first_ actor you create?
As we have seen in the previous chapters, to create a top-level actor one must call `system.actorOf()`. This does
not create a "freestanding" actor though, instead, it injects the corresponding actor as a child into an already
existing tree:

![box diagram of the architecture](diagrams/actor_top_tree.png)

As you see, creating actors from the "top" injects those actors under the path `/user/`, so for example creating
an actor named `myActor` will end up having the path `/user/myActor`. In fact, there are three already existing
actors in the system:

 - `/` the so-called _root guardian_. This is the parent of all actors in the system, and the last one to stop
   when the system itself is terminated.
 - `/user` the _guardian_. **This is the parent actor for all user created actors**. The name `user` should not confuse
   you, it has nothing to do with the logged in user, nor user handling in general. This name really means _userspace_
   as this is the place where actors that do not access Akka internals live, i.e. all the actors created by users of the Akka library. Every actor you will create will have the constant path `/user/` prepended to it.
 - `/system` the _system guardian_.

The names of these built-in actors contain _guardian_ because these are _supervising_ every actor living as a child
of them, i.e. under their path. We will explain supervision in more detail, all you need to know now is that every
unhandled failure from actors bubbles up to their parent that, in turn, can decide how to handle this failure. These
predefined actors are guardians in the sense that they are the final lines of defense, where all unhandled failures
from user, or system, actors end up.

> Does the root guardian (the root path `/`) have a parent? As it turns out, it has. This special entity is called
> the "Bubble-Walker". This special entity is invisible for the user and only has uses internally.

### Structure of an ActorRef and Paths of Actors

The easiest way to see this in action is to simply print `ActorRef` instances. In this small experiment, we print
the reference of the first actor we create and then we create a child of this actor, and print its reference. We have
already created actors with `system.actorOf()`, which creates an actor under `/user` directly. We call this kind
of actors _top level_, even though in practice they are not on the top of the hierarchy, only on the top of the
_user defined_ hierarchy. Since in practice we usually concern ourselves about actors under `/user` this is still a
convenient terminology, and we will stick to it.

Creating a non-top-level actor is possible from any actor, by invoking `context.actorOf()` which has the exact same
signature as its top-level counterpart. This is how it looks like in practice:

@@snip [Hello.scala]($code$/scala/tutorial_1/ActorHierarchyExperiments.scala) { #print-refs }

We see that the following two lines are printed

```
First : Actor[akka://testSystem/user/first-actor#1053618476]
Second: Actor[akka://testSystem/user/first-actor/second-actor#-1544706041]
```

First, we notice that all of the paths start with `akka://testSystem/`. Since all actor references are valid URLs, there
is a protocol field needed, which is `akka://` in the case of actors. Then, just like on the World Wide Web, the system
is identified. In our case, this is `testSystem`, but could be any other name (if remote communication between multiple
systems is enabled this name is the hostname of the system so other systems can find it on the network). Our two actors,
as we have discussed before, live under user, and form a hierarchy:

 * `akka://testSystem/user/first-actor` is the first actor we created, which lives directly under the user guardian,
   `/user`
 * `akka://testSystem/user/first-actor/second-actor` is the second actor we created, using `context.actorOf`. As we
   see it lives directly under the first actor.

The last part of the actor reference, like `#1053618476` is a unique identifier of the actor living under the path.
This is usually not something the user needs to be concerned with, and we leave the discussion of this field for later.

### Hierarchy and Lifecycle of Actors

We have so far seen that actors are organized into a **strict hierarchy**. This hierarchy consists of a predefined
upper layer of three actors (the root guardian, the user guardian, and the system guardian), thereafter the user created
top-level actors (those directly living under `/user`) and the children of those. We now understand what the hierarchy
looks like, but there are some nagging unanswered questions: _Why do we need this hierarchy? What is it used for?_

The first use of the hierarchy is to manage the lifecycle of actors. Actors pop into existence when created, then later,
at user requests, they are stopped. Whenever an actor is stopped, all of its children are _recursively stopped_ too.
This is a very useful property and greatly simplifies cleaning up resources and avoiding resource leaks (like open
sockets files, etc.). In fact, one of the overlooked difficulties when dealing with low-level multi-threaded code is
the lifecycle management of various concurrent resources.

Stopping an actor can be done by calling `context.stop(actorRef)`. **It is considered a bad practice to stop arbitrary
actors this way**. The recommended pattern is to call `context.stop(self)` inside an actor to stop itself, usually as
a response to some user defined stop message or when the actor is done with its job.

The actor API exposes many lifecycle hooks that the actor implementation can override. The most commonly used are
`preStart()` and `postStop()`.

 * `preStart()` is invoked after the actor has started but before it processes its first message.
 * `postStop()` is invoked just before the actor stops. No messages are processed after this point.

Again, we can try out all this with a simple experiment:

@@snip [Hello.scala]($code$/scala/tutorial_1/ActorHierarchyExperiments.scala) { #start-stop }

After running it, we get the output

```
first started
second started
second stopped
first stopped
```

We see that when we stopped actor `first` it recursively stopped actor `second` and thereafter it stopped itself.
This ordering is strict, _all_ `postStop()` hooks of the children are called before the `postStop()` hook of the parent
is called.

The family of these lifecycle hooks is rich, and we recommend reading [the actor lifecycle](http://doc.akka.io/docs/akka/current/scala/actors.html#Actor_Lifecycle) section of the reference for all details.

### Hierarchy and Failure Handling (Supervision)

Parents and children are not only connected by their lifecycles. Whenever an actor fails (throws an exception or
an unhandled exception bubbles out from `receive`) it is temporarily suspended. The failure information is propagated
to the parent, which decides how to handle the exception caused by the child actor. The default _supervisor strategy_ is to
stop and restart the child. If you don't change the default strategy all failures result in a restart. We won't change
the default strategy in this simple experiment:

@@snip [Hello.scala]($code$/scala/tutorial_1/ActorHierarchyExperiments.scala) { #supervise }

After running the snippet, we see the following output on the console:

```
supervised actor started
supervised actor fails now
supervised actor stopped
supervised actor started
[ERROR] [03/29/2017 10:47:14.150] [testSystem-akka.actor.default-dispatcher-2] [akka://testSystem/user/supervising-actor/supervised-actor] I failed!
java.lang.Exception: I failed!
        at tutorial_1.SupervisedActor$$anonfun$receive$4.applyOrElse(ActorHierarchyExperiments.scala:57)
        at akka.actor.Actor$class.aroundReceive(Actor.scala:513)
        at tutorial_1.SupervisedActor.aroundReceive(ActorHierarchyExperiments.scala:47)
        at akka.actor.ActorCell.receiveMessage(ActorCell.scala:519)
        at akka.actor.ActorCell.invoke(ActorCell.scala:488)
        at akka.dispatch.Mailbox.processMailbox(Mailbox.scala:257)
        at akka.dispatch.Mailbox.run(Mailbox.scala:224)
        at akka.dispatch.Mailbox.exec(Mailbox.scala:234)
        at akka.dispatch.forkjoin.ForkJoinTask.doExec(ForkJoinTask.java:260)
        at akka.dispatch.forkjoin.ForkJoinPool$WorkQueue.runTask(ForkJoinPool.java:1339)
        at akka.dispatch.forkjoin.ForkJoinPool.runWorker(ForkJoinPool.java:1979)
        at akka.dispatch.forkjoin.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:107)
```

We see that after failure the actor is stopped and immediately started. We also see a log entry reporting the
exception that was handled, in this case, our test exception. In this example we use `preStart()` and `postStop()` hooks
which are the default to be called after and before restarts, so we cannot distinguish from inside the actor if it
was started for the first time or restarted. This is usually the right thing to do, the purpose of the restart is to
set the actor in a known-good state, which usually means a clean starting stage. **What actually happens though is
that the `preRestart()` and `postRestart()` methods are called which, if not overridden, by default delegate to
`postStop()` and `preStart()` respectively**. You can experiment with overriding these additional methods and see
how the output changes.

For the impatient, we also recommend looking into the [supervision reference page](http://doc.akka.io/docs/akka/current/general/supervision.html) for more in-depth
details.

### The First Actor

Actors are organized into a strict tree, where the lifecycle of every child is tied to the parent and where parents
are responsible for deciding the fate of failed children. At first, it might not be evident how to map our problem
to such a tree, but in practice, this is easier than it looks. All we need to do is to rewrite our architecture diagram
that contained nested boxes into a tree:

![actor tree diagram of the architecture](diagrams/arch_tree_diagram.png)

In simple terms, every component manages the lifecycle of the subcomponents. No subcomponent can outlive the parent
component. This is exactly how the actor hierarchy works. Furthermore, it is desirable that a component handles the failure
of its subcomponents. Together, these two desirable properties lead to the conclusion that the "contained-in" relationship of components should be mapped to the
"children-of" relationship of actors.

The remaining question is how to map the top-level components to actors. It might be tempting to create the actors
representing the main components as top-level actors. We instead, recommend creating an explicit component that
represents the whole application. In other words, we will have a single top-level actor in our actor system and have
the main components as children of this actor.

The first actor happens to be rather simple now, as we have not implemented any of the components yet. What is new
is that we have dropped using `println()` and instead use the `ActorLogging` helper trait which allows us to use the
logging facility built into Akka directly. Furthermore, we are using a recommended creational pattern for actors; define a `props()` method in the [companion object](http://docs.scala-lang.org/tutorials/tour/singleton-objects.html#companions) of the actor:

@@snip [Hello.scala]($code$/scala/tutorial_1/IotSupervisor.scala) { #iot-supervisor }

All we need now is to tie this up with a class with the `main` entry point:

@@snip [Hello.scala]($code$/scala/tutorial_1/IotApp.scala) { #iot-app }

This application does very little for now, but we have the first actor in place and we are ready to extend it further.

## What is next?

In the following chapters we will grow the application step-by-step:

 1. We will create the representation for a device
 2. We create the device management component
 3. We add query capabilities to device groups
 4. We add the dashboard component
