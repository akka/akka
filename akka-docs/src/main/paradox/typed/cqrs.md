# CQRS

@ref:[EventSourcedBehavior](persistence.md)s along with @ref:[Persistence Query](../persistence-query.md)'s `EventsByTag` query can be used to implement
 Command Query Responsibility Segregation (CQRS).

The [CQRS with Akka 2.6 video](https://akka.io/blog/news/2020/02/05/akka-cqrs-video) is a good starting point for
learning how to use `eventsByTag` to implement CQRS with Akka. Also, watch the introduction to 
[Event Sourcing with Akka 2.6 video](https://akka.io/blog/news/2020/01/07/akka-event-sourcing-video).
 
The @java[@extref[CQRS example project](samples:akka-samples-cqrs-java)]@scala[@extref[CQRS example project](samples:akka-samples-cqrs-scala)]
shows how to do this, including scaling read side processors for building projections.
In the sample the events are tagged to be consumed by even processors to build other representations
from the events, or publish the events to other services.

 
