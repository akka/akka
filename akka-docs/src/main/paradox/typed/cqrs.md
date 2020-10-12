# CQRS

@ref:[EventSourcedBehavior](persistence.md)s along with [Akka Projections](https://doc.akka.io/docs/akka-projection/current/) can be used to implement
 Command Query Responsibility Segregation (CQRS).
 
The @java[@extref[CQRS example project](samples:akka-samples-cqrs-java)]@scala[@extref[CQRS example project](samples:akka-samples-cqrs-scala)]
shows how to do this, including scaling read side processors for building projections.
In the sample the events are tagged to be consumed by even processors to build other representations
from the events, or publish the events to other services.

 
