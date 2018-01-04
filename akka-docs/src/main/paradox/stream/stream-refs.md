# Akka Streams over network boundaries 

Stream references, or "stream refs" for short, allow running Akka Streams across multiple nodes within 
an Akka Cluster. 

Unlike heavier "streaming data processing" frameworks, Akka Streams are not "deployed" nor automatically distributed.
Akka stream refs are, as the name implies, references to existing parts of a stream, and can be used to create a 
distributed processing framework or introduce such capabilities in specific parts of your application, however they 
are not on that level of abstraction by themselves. 
  
Stream refs are trivial to make use of in existing clustered Akka applications, and require no additional configuration 
or setup. They automatically maintain flow-control / back-pressure over the network, and employ Akka's failure detection 
mechanisms to fail-fast ("let it crash!") in the case of failures of remote nodes. They can be seen as an implementation 
of the [Work Pulling Pattern](http://www.michaelpollmeier.com/akka-work-pulling-pattern), which one would otherwise 
implement manually.


@@@ note
  A useful way to think about stream refs is: 
  "like an `ActorRef`, but for Akka Streams's `Source` and `Sink`".
  
  Since they refer to an already existing, possibly remote, `Sink` or `Source`.
  This is not to be mistaken with deploying streams remotely, which this feature is not intended for.
@@@


@@@ note
  Since Akka Streams are an implementation of Reactive Streams, by induction, 
  one can also say that stream refs allow running *Reactive Streams over the network*.
@@@

## Stream References

The prime use case for stream refs is to replace raw actor or HTTP messaging between systems where a long
running stream of data is expected between two entities. Often times, they can be used to effectively achieve point 
to point streaming without the need of setting up additional message brokers or similar secondary clusters.

Stream refs are well suited for any system in which you need to send messages between nodes and need to do so in a 
flow-controlled fashion. Typical examples include sending work requests to worker nodes, as fast as possible, but
not faster than the worker node can process them, or sending data elements which the downstream may be slow at processing.
It is recommended to mix and introduce stream refs in Actor messaging based systems, where the actor messaging is used to
orchestrate and prepare such message flows, and later the stream refs are used to do the flow-controlled message transfer.  

Stream refs are not persistent, however it is simple to build a resume-able stream by introducing such protocol
on the actor messaging layer. Stream refs are absolutely expected to be sent over Akka remoting to other nodes 
within a cluster, and as such, complement and do not compete with plain Actor messaging. 
Actors would usually be used to establish the stream, by means of some initial message saying 
"I want to offer you many log elements (the stream ref)", or alternatively in the opposite way "If you need
to send me much data, here is the stream ref you can use to do so".   

Since the two sides ("local" and "remote") of reach reference may be confusing to simply refer to as
"remote" and "local" -- since either side can be seen as "local" or "remote" depending how we look at it --
we propose to use the terminology "origin" and "target", which is defined by where the stream ref was created.
For `SourceRef`s, the "origin" is the side which has the data that it is going to stream out. For `SinkRef`s
the "origin" side is the actor system that is ready to receive the data and has allocated the ref. Those
two may be seen as duals of each other, however to explain patterns about sharing references, we found this
 wording to be rather useful.  

### Source Refs - offering streaming data over network

A @scala[@scaladoc[`SourceRef`](akka.stream.SourceRef)]@java[@javadoc[`SourceRef`](akka.stream.SourceRef)]
can be offered to a remote actor system in order for it to consume some source of data that we have prepared
locally. 

In order to share a `Source` with a remote endpoint you need to materialize it by running it into the `Sink.sourceRef`.
That sink materializes the `SourceRef` that you can then send to other nodes. Please note that it materializes into a 
`Future` so you will have to use the pipeTo

Scala
:   @@snip [FlowStreamRefsDocSpec.scala]($code$/scala/docs/stream/FlowStreamRefsDocSpec.scala) { #offer-source }

The origin actor which creates and owns the Source could also perform some validation or additional setup
when preparing the source. Once it has handed out the `SourceRef` the remote side can run it like this:

Scala
:   @@snip [FlowStreamRefsDocSpec.scala]($code$/scala/docs/stream/FlowStreamRefsDocSpec.scala) { #offer-source-use }

The process of preparing and running a `SourceRef` powered distributed stream is shown by the animation below:

![SourceRef usage animation](../images/source-ref-animation.gif)

@@@ warning
  A `SourceRef` is *by design* "single-shot". i.e. it may only be materialized once.
  This is in order to not complicate the mental model what materializing such value would mean.
  
  By being single-shot, we always know what it means, and on top of those semantics offer a fan-out
  by emitting multiple `SourceRef`s which target the same `Source` that uses `Broadcast`.
  This also allows for fine grained control how many streams a system can expect to be running 
  at the same time, which is useful for capacity planning and "allowed number of concurrent streams
  limiting" of clients.
@@@

### Sink Refs - offering to receive streaming data

The dual of source references are A @scala[@scaladoc[`SourceRef`](akka.stream.SinkRef)]@java[@javadoc[`SourceRef`](akka.stream.SinkRef)]s. They can be used to offer the other side the capability to 
send to the *origin* side data in a streaming, flow-controlled fashion. The origin here allocates a Sink,
which could be as simple as a `Sink.foreach` or as advanced as a complex sink which streams the incoming data
into various other systems (e.g. any of the Alpakka provided Sinks).

@@@ note
  To form a good mental model of `SinkRef`s, you can think of them as being similar to "passive mode" in FTP.
@@@

Scala
:   @@snip [FlowStreamRefsDocSpec.scala]($code$/scala/docs/stream/FlowStreamRefsDocSpec.scala) { #offer-sink }

Using the offered `SinkRef` to send data to the origin of the Sink is also simple, as we can treat the 
SinkRef just as any other Sink and directly `runWith` or `run` with it.

Scala
:   @@snip [FlowStreamRefsDocSpec.scala]($code$/scala/docs/stream/FlowStreamRefsDocSpec.scala) { #offer-sink-use }



![simple-graph-example.png](../images/sink-ref-dance.png)

@@@ warning
  A `SinkeRef` is *by design* "single-shot". i.e. it may only be materialized once.
  This is in order to not complicate the mental model what materializing such value would mean.
  
  If you have an use case for building a fan-in operation accepting writes from multiple remote nodes,
  you can build your Sink and prepend it with a `Merge` stage, each time materializing a new `SinkRef`
  targeting that Merge. This has the added benefit of giving you full control how to merge these streams 
  (i.e. by using "merge preferred" or any other variation of the fan-in stages).
@@@

## Bulk Stream References

@@@ warning
  Not yet implemented. See ticket ...... FIXME, ticket number 
@@@

Bulk stream references can be used to create simple to use side-channels to transfer humongous amounts 
of data such as huge log files, messages or even media, with as much ease as if it was a trivial local stream.

Connections for each stream ref bulk stream ref are established independently, and do not utilise
actor messaging (which is not designed for such bulk transfers, but rather small messages).

## Configuration

### Stream reference subscription timeouts

All stream references have a subscription timeout, which is intended to prevent resource leaks
in situations in which a remote node would requests the allocation of many streams yet never actually run
them. In order to prevent this, each stream reference has a default timeout (of 30 seconds), after which
if it's "handed out" side has not been materialized, the origin will terminate with a timeout exception,
and IF the remote side eventually would be run afterwards, it would also immediately fail with an exception
pointing out that the origin seems to be missing.

Since these timeouts are often very different based on the kind of stream offered, and there can be 
many different kinds of them in the same application, it is possible to not only configure this setting
globally (`akka.stream.materializer.stream-ref.subscription-timeout`), but also via attributes:



Scala
:   @@snip [FlowStreamRefsDocSpec.scala]($code$/scala/docs/stream/FlowStreamRefsDocSpec.scala) { #attr-sub-timeout }



## General configuration

Other settings can be set globally, in your `application.conf`, by overriding any of the following values
in the `akka.stream.materializer.stream-ref.*` keyspace:

@@snip [reference.conf]($akka$/akka-stream/src/main/resources/reference.conf) { #stream-ref }
