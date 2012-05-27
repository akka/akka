.. _circuit-breaker:

###############
Circuit Breaker
###############

==================
Why are they used?
==================
A circuit breaker is used to provide stability and prevent cascading failures in distributed
systems.  These should be used in conjunction with judicious timeouts at the interfaces between
remote systems to prevent the failure of a single component from bringing down all components.

As an example, we have a web application interacting with a remote third party web service.  
Let's say the third party has oversold their capacity and their database melts down under load.  
Assume that the database fails in such a way that it takes a very long time to hand back an
error to the third party web service.  This in turn makes calls fail after a long period of 
time.  Back to our web application, the users have noticed that their form submissions take
much longer seeming to hang.  Well the users do what they know to do which is use the refresh
button, adding more requests to their already running requests.  This eventually causes the 
failure of the web application due to resource exhaustion.  This will affect all users, even
those who are not using functionality dependent on this third party web service.

Introducing circuit breakers on the web service call would cause the requests to begin to 
fail-fast, letting the user know that something is wrong and that they need not refresh 
their request.  This also confines the failure behavior to only those users that are using
functionality dependent on the third party, other users are no longer affected as there is no
resource exhaustion.  Circuit breakers can also allow savvy developers to mark portions of
the site that use the functionality unavailable, or perhaps show some cached content as 
appropriate while the breaker is open.

The Akka library provides an implementation of a circuit breaker called 
:class:`akka.pattern.CircuitBreaker` which has the behavior described below.

=================
What do they do?
=================
* During normal operation, a circuit breaker is in the `Closed` state:
	* Exceptions or calls exceeding the configured `callTimeout` increment a failure counter
	* Successes reset the failure count to zero 
	* When the failure counter reaches a `maxFailures` count, the breaker is tripped into `Open` state
* While in `Open` state:
	* All calls fail-fast with a :class:`CircuitBreakerOpenException`
	* After the configured `resetTimeout`, the circuit breaker enters a `Half-Open` state
* In `Half-Open` state:
	* The first call attempted is allowed through without failing fast
	* All other calls fail-fast with an exception just as in `Open` state
	* If the first call succeeds, the breaker is reset back to `Closed` state
	* If the first call fails, the breaker is tripped again into the `Open` state for another full `resetTimeout`
* State transition listeners: 
	* Callbacks can be provided for every state entry via `onOpen`, `onClose`, and `onHalfOpen`
	* These are executed in the :class:`ExecutionContext` provided. 

.. graphviz::

	digraph circuit_breaker {
		rankdir = "LR";
		size = "6,5";
		graph [ bgcolor = "transparent" ]
		node [ fontname = "Helvetica",
					 fontsize = 14,
					 shape = circle, 
					 color = white, 
					 style = filled ];
	  edge [ fontname = "Helvetica", fontsize = 12 ]
		Closed [ fillcolor = green2 ];
		"Half-Open" [fillcolor = yellow2 ];
		Open [ fillcolor = red2 ];
		Closed -> Closed [ label = "Success" ];
		"Half-Open" -> Open [ label = "Trip Breaker" ];
		"Half-Open" -> Closed [ label = "Reset Breaker" ];
		Closed -> Open [ label = "Trip Breaker" ];
		Open -> Open [ label = "Calls failing fast" ];
		Open -> "Half-Open" [ label = "Attempt Reset" ];
	}

========
Examples
========

--------------
Initialization
--------------

Here's how a :class:`CircuitBreaker` would be configured for:
  * 5 maximum failures
  * a call timeout of 10 seconds 
  * a reset timeout of 1 minute

^^^^^^^
Scala
^^^^^^^

.. includecode:: code/docs/circuitbreaker/CircuitBreakerDocSpec.scala
   :include: imports1,circuit-breaker-initialization

^^^^^^^
Java
^^^^^^^

.. includecode:: code/docs/circuitbreaker/CircuitBreakerDocSample.java
   :include: imports1,circuit-breaker-initialization

---------------
Call Protection
---------------

Here's how the :class:`CircuitBreaker` would be used to protect an asynchronous
call as well as a synchronous one:

^^^^^^^
Scala
^^^^^^^

.. includecode:: code/docs/circuitbreaker/CircuitBreakerDocSpec.scala
   :include: circuit-breaker-usage

^^^^^^
Java
^^^^^^

.. includecode:: code/docs/circuitbreaker/CircuitBreakerDocSample.java
   :include: circuit-breaker-usage

.. note::

	Using the :class:`CircuitBreaker` companion object's `apply` or `create` methods
	will return a :class:`CircuitBreaker` where callbacks are executed in the caller's thread.
	This can be useful if the asynchronous :class:`Future` behavior is unnecessary, for
	example invoking a synchronous-only API.