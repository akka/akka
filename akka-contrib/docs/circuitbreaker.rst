.. _circuit-breaker:

Circuit-Breaker Actor
=====================

This is an alternative implementation of the [Akka Circuit Breaker Pattern](http://doc.akka.io/docs/akka/snapshot/common/circuitbreaker.html).
The main difference is that it is intended to be used only for request-reply interactions with an actor using the Circuit-Breaker as a proxy of the target one
in order to provide the same failfast functionalities and a protocol similar to the circuit-breaker implementation in Akka.


### Usage

Let's assume we have an actor wrapping a back-end service and able to respond to ``Request`` calls with a ``Response`` object
containing an ``Either[String, String]`` to map successful and failed responses. The service is also potentially slowing down
because of the workload.

A simple implementation can be given by this class

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/SimpleService.scala#simple-service


If we want to interface with this service using the Circuit Breaker we can use two approaches:

Using a non-conversational approach:

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreaker.scala#basic-sample

Using the ``ask`` pattern, in this case it is useful to be able to map circuit open failures to the same type of failures
returned by the service (a ``Left[String]`` in our case):

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreakerAsk.scala#ask-sample


If it is not possible to define define a specific error response, you can map the Open Circuit notification to a failure.
That also means that your ``CircuitBreakerActor`` will be useful to protect you from time out for extra workload or
temporary failures in the target actor includecode:: code/docs/stream/io/StreamFileDocSpec.scala#file-source

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/circuitbreaker/sample/CircuitBreakerAskWithFailure.scala#ask-with-failure-sample

To send messages to the `target` actor without expecting any response you can wrap your message in a ``TellOnly`` or a ``Passthrough``
envelope. The difference between the two is that ``TellOnly`` will forward the message only when in closed mode and
``Passthrough`` will do it in any state.
