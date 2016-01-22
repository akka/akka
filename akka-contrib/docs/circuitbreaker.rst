.. _circuit-breaker-proxy:

Circuit-Breaker Actor
=====================

This is an alternative implementation of the :ref:`Akka Circuit Breaker Pattern <circuit-breaker>`.
The main difference is that it is intended to be used only for request-reply interactions with an actor using the Circuit-Breaker as a proxy of the target one
in order to provide the same failfast functionalities and a protocol similar to the circuit-breaker implementation in Akka.


### Usage

Let's assume we have an actor wrapping a back-end service and able to respond to ``Request`` calls with a ``Response`` object
containing an ``Either[String, String]`` to map successful and failed responses. The service is also potentially slowing down
because of the workload.

A simple implementation can be given by this class

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreaker.scala#simple-service


If we want to interface with this service using the Circuit Breaker we can use two approaches:

Using a non-conversational approach:

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreaker.scala#basic-sample

Using the ``ask`` pattern, in this case it is useful to be able to map circuit open failures to the same type of failures
returned by the service (a ``Left[String]`` in our case):

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreaker.scala#ask-sample

If it is not possible to define define a specific error response, you can map the Open Circuit notification to a failure.
That also means that your ``CircuitBreakerActor`` will be useful to protect you from time out for extra workload or
temporary failures in the target actor.
You can decide to do that in two ways:

The first is to use the ``askWithCircuitBreaker`` method on the ``ActorRef`` or ``ActorSelection`` instance pointing to
your circuit breaker proxy (enabled by importing ``import akka.contrib.circuitbreaker.Implicits.askWithCircuitBreaker``)

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreaker.scala#ask-with-circuit-breaker-sample

The second is to map the future response of your ``ask`` pattern application with the ``failForOpenCircuit``
enabled by importing ``import akka.contrib.circuitbreaker.Implicits.futureExtensions``

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreaker.scala#ask-with-failure-sample

#### Direct Communication With The Target Actor

To send messages to the `target` actor without expecting any response you can wrap your message in a ``TellOnly`` or a ``Passthrough``
envelope. The difference between the two is that ``TellOnly`` will forward the message only when in closed mode and
``Passthrough`` will do it in any state. You can for example use the ``Passthrough`` envelope to wrap a ``PoisonPill``
message to terminate the target actor. That will cause the circuit breaker proxy to be terminated too

