# HowTo: Common Patterns

This section lists common actor patterns which have been found to be useful,
elegant or instructive. Anything is welcome, example topics being message
routing strategies, supervision patterns, restart handling, etc. As a special
bonus, additions to this section are marked with the contributor’s name, and it
would be nice if every Akka user who finds a recurring pattern in his or her
code could share it for the profit of all. Where applicable it might also make
sense to add to the `akka.pattern` package for creating an [OTP-like library](http://www.erlang.org/doc/man_index.html).

@@@ div { .group-java }

You might find some of the patterns described in the Scala chapter of
this page useful even though the example code is written in Scala.

@@@

@@@ div { .group-scala }

## Throttling Messages

Contributed by: Kaspar Fischer

"A message throttler that ensures that messages are not sent out at too high a rate."

The pattern is described in [Throttling Messages in Akka 2](http://letitcrash.com/post/28901663062/throttling-messages-in-akka-2).

## Balancing Workload Across Nodes

Contributed by: Derek Wyatt

"Often times, people want the functionality of the BalancingDispatcher with the
stipulation that the Actors doing the work have distinct Mailboxes on remote
nodes. In this post we’ll explore the implementation of such a concept."

The pattern is described [Balancing Workload across Nodes with Akka 2](http://letitcrash.com/post/29044669086/balancing-workload-across-nodes-with-akka-2).

## Work Pulling Pattern to throttle and distribute work, and prevent mailbox overflow

Contributed by: Michael Pollmeier

"This pattern ensures that your mailboxes don’t overflow if creating work is fast than
actually doing it – which can lead to out of memory errors when the mailboxes
eventually become too full. It also let’s you distribute work around your cluster,
scale dynamically scale and is completely non-blocking. This pattern is a
specialisation of the above 'Balancing Workload Pattern'."

The pattern is described [Work Pulling Pattern to prevent mailbox overflow, throttle and distribute work](http://www.michaelpollmeier.com/akka-work-pulling-pattern).

## Ordered Termination

Contributed by: Derek Wyatt

"When an Actor stops, its children stop in an undefined order. Child termination is
asynchronous and thus non-deterministic.

If an Actor has children that have order dependencies, then you might need to ensure
a particular shutdown order of those children so that their postStop() methods get
called in the right order."

The pattern is described [An Akka 2 Terminator](http://letitcrash.com/post/29773618510/an-akka-2-terminator).

## Akka AMQP Proxies

Contributed by: Fabrice Drouin

"“AMQP proxies” is a simple way of integrating AMQP with Akka to distribute jobs across a network of computing nodes.
You still write “local” code, have very little to configure, and end up with a distributed, elastic,
fault-tolerant grid where computing nodes can be written in nearly every programming language."

The pattern is described [Akka AMQP Proxies](http://letitcrash.com/post/29988753572/akka-amqp-proxies).

## Shutdown Patterns in Akka 2

Contributed by: Derek Wyatt

“How do you tell Akka to shut down the ActorSystem when everything’s finished?
It turns out that there’s no magical flag for this, no configuration setting, no special callback you can register for,
and neither will the illustrious shutdown fairy grace your application with her glorious presence at that perfect moment.
She’s just plain mean.

In this post, we’ll discuss why this is the case and provide you with a simple option for shutting down “at the right time”,
as well as a not-so-simple-option for doing the exact same thing."

The pattern is described [Shutdown Patterns in Akka 2](http://letitcrash.com/post/30165507578/shutdown-patterns-in-akka-2).

## Distributed (in-memory) graph processing with Akka

Contributed by: Adelbert Chang

"Graphs have always been an interesting structure to study in both mathematics and computer science (among other fields),
and have become even more interesting in the context of online social networks such as Facebook and Twitter,
whose underlying network structures are nicely represented by graphs."

The pattern is described [Distributed In-Memory Graph Processing with Akka](http://letitcrash.com/post/30257014291/distributed-in-memory-graph-processing-with-akka).

## Case Study: An Auto-Updating Cache Using Actors

Contributed by: Eric Pederson

"We recently needed to build a caching system in front of a slow backend system with the following requirements:

The data in the backend system is constantly being updated so the caches need to be updated every N minutes.
Requests to the backend system need to be throttled.
The caching system we built used Akka actors and Scala’s support for functions as first class objects."

The pattern is described [Case Study: An Auto-Updating Cache using Actors](http://letitcrash.com/post/30509298968/case-study-an-auto-updating-cache-using-actors).

## Discovering message flows in actor systems with the Spider Pattern

Contributed by: Raymond Roestenburg

"Building actor systems is fun but debugging them can be difficult, you mostly end up browsing through many log files
on several machines to find out what’s going on. I’m sure you have browsed through logs and thought,
“Hey, where did that message go?”, “Why did this message cause that effect” or “Why did this actor never get a message?”

This is where the Spider pattern comes in."

The pattern is described [Discovering Message Flows in Actor System with the Spider Pattern](http://letitcrash.com/post/30585282971/discovering-message-flows-in-actor-systems-with-the).

@@@

## Scheduling Periodic Messages

See @ref:[Actor Timers](actors.md#actors-timers)

## Single-Use Actor Trees with High-Level Error Reporting

*Contributed by: Rick Latrine*

A nice way to enter the actor world from java is the use of Patterns.ask().
This method starts a temporary actor to forward the message and collect the result from the actor to be "asked".
In case of errors within the asked actor the default supervision handling will take over.
The caller of Patterns.ask() will *not* be notified.

If that caller is interested in such an exception, they must make sure that the asked actor replies with Status.Failure(Throwable).
Behind the asked actor a complex actor hierarchy might be spawned to accomplish asynchronous work.
Then supervision is the established way to control error handling.

Unfortunately the asked actor must know about supervision and must catch the exceptions.
Such an actor is unlikely to be reused in a different actor hierarchy and contains crippled try/catch blocks.

This pattern provides a way to encapsulate supervision and error propagation to the temporary actor.
Finally the promise returned by Patterns.ask() is fulfilled as a failure, including the exception
(see also @ref:[Java 8 Compatibility](java8-compat.md) for Java compatibility).

Let's have a look at the example code:

@@snip [SupervisedAsk.java]($code$/java/jdocs/pattern/SupervisedAsk.java)

In the askOf method the SupervisorCreator is sent the user message.
The SupervisorCreator creates a SupervisorActor and forwards the message.
This prevents the actor system from overloading due to actor creations.
The SupervisorActor is responsible to create the user actor, forwards the message, handles actor termination and supervision.
Additionally the SupervisorActor stops the user actor if execution time expired.

In case of an exception the supervisor tells the temporary actor which exception was thrown.
Afterwards the actor hierarchy is stopped.

Finally we are able to execute an actor and receive the results or exceptions.

@@snip [SupervisedAskSpec.java]($code$/java/jdocs/pattern/SupervisedAskSpec.java)

@@@