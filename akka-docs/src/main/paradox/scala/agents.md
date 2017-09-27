# Agents

Agents in Akka are inspired by [agents in Clojure](http://clojure.org/agents).

@@@ warning { title="Deprecation warning" }

Agents have been deprecated and are scheduled for removal
in the next major version. We have found that their leaky abstraction (they do not
work over the network) make them inferior to pure Actors, and in face of the soon
inclusion of Akka Typed we see little value in maintaining the current Agents.

@@@

Agents provide asynchronous change of individual locations. Agents are bound to
a single storage location for their lifetime, and only allow mutation of that
location (to a new state) to occur as a result of an action. Update actions are
functions that are asynchronously applied to the Agent's state and whose return
value becomes the Agent's new state. The state of an Agent should be immutable.

While updates to Agents are asynchronous, the state of an Agent is always
immediately available for reading by any thread (using `get` @scala[or `apply`])
without any messages.

Agents are reactive. The update actions of all Agents get interleaved amongst
threads in an `ExecutionContext`. At any point in time, at most one `send` action for
each Agent is being executed. Actions dispatched to an agent from another thread
will occur in the order they were sent, potentially interleaved with actions
dispatched to the same agent from other threads.

@@@ note

Agents are local to the node on which they are created. This implies that you
should generally not include them in messages that may be passed to remote Actors
or as constructor parameters for remote Actors; those remote Actors will not be able to
read or update the Agent.

@@@

## Creating Agents

Agents are created by invoking @scala[`Agent(value)`] @java[`new Agent<ValueType>(value, executionContext)`] passing in the Agent's initial
value and providing an @scala[implicit] `ExecutionContext` to be used for it, 
@scala[for these examples we're going to use the default global one, but YMMV:]

Scala
:  @@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #create }

Java
:  @@snip [AgentDocTest.java]($code$/java/jdocs/agent/AgentDocTest.java) { #import-agent #create type=java }

## Reading an Agent's value

Agents can be dereferenced (you can get an Agent's value) by invoking the Agent
with @scala[parentheses] @java[`get()`] like this:

Scala
:  @@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #read-apply #read-get  }

Java
:  @@snip [AgentDocTest.java]($code$/java/jdocs/agent/AgentDocTest.java) { #read-get type=java }

Reading an Agent's current value does not involve any message passing and
happens immediately. So while updates to an Agent are asynchronous, reading the
state of an Agent is synchronous.

@@@ div { .group-java }

You can also get a `Future` to the Agents value, that will be completed after the
currently queued updates have completed:

@@snip [AgentDocTest.java]($code$/java/jdocs/agent/AgentDocTest.java) { #import-future #read-future type=java }

See @ref:[Futures](futures.md) for more information on `Futures`.

@@@ 

## Updating Agents (send & alter)

You update an Agent by sending a function @java[(`akka.dispatch.Mapper`)] that transforms the current value or
by sending just a new value. The Agent will apply the new value or function
atomically and asynchronously. The update is done in a fire-forget manner and
you are only guaranteed that it will be applied. There is no guarantee of when
the update will be applied but dispatches to an Agent from a single thread will
occur in order. You apply a value or a function by invoking the `send`
function.

Scala
:  @@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #send }

Java
:  @@snip [AgentDocTest.java]($code$/java/jdocs/agent/AgentDocTest.java) { #import-function #send type=java }

You can also dispatch a function to update the internal state but on its own
thread. This does not use the reactive thread pool and can be used for
long-running or blocking operations. You do this with the `sendOff`
method. Dispatches using either `sendOff` or `send` will still be executed
in order.

Scala
:  @@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #send-off }

Java
:  @@snip [AgentDocTest.java]($code$/java/jdocs/agent/AgentDocTest.java) { #import-function #send-off type=java }

All `send` methods also have a corresponding `alter` method that returns a `Future`.
See @ref:[`Future`s](futures.md) for more information on `Future`s.

Scala
:  @@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #alter #alter-off }

Java
:  @@snip [AgentDocTest.java]($code$/java/jdocs/agent/AgentDocTest.java) { #import-future #import-function #alter #alter-off type=java }

@@@ div { .group-scala }

## Awaiting an Agent's value

You can also get a `Future` to the Agents value, that will be completed after the
currently queued updates have completed:

@@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #read-future }

See @ref:[`Future`s](futures.md) for more information on `Future`s.

## Monadic usage

Agents are also monadic, allowing you to compose operations using
for-comprehensions. In monadic usage, new Agents are created leaving the
original Agents untouched. So the old values (Agents) are still available
as-is. They are so-called 'persistent'.

Example of monadic usage:

@@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #monadic-example }

@@@

## Configuration

There are several configuration properties for the agents module, please refer
to the @ref:[reference configuration](general/configuration.md#config-akka-agent).

## Deprecated Transactional Agents

Agents participating in enclosing STM transaction is a deprecated feature in 2.3.

If an Agent is used within an enclosing @java[`Scala STM`] transaction, then it will participate in
that transaction. If you send to an Agent within a transaction then the dispatch
to the Agent will be held until that transaction commits, and discarded if the
transaction is aborted. @scala[Here's an example:]

@@@ div { .group-scala }

@@snip [AgentDocSpec.scala]($code$/scala/docs/agent/AgentDocSpec.scala) { #transfer-example }

@@@