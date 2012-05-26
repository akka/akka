.. _Circuit Breaker:

###############
Circuit Breaker
###############

A circuit breaker is used to provide stability and prevent cascading failures in distributed
systems.  These should be used in conjunction with judicious timeouts at the interfaces between
remote systems to prevent the failure of a single component from bringing down all components.

The Akka library provides an implementation of a circuit breaker called 
:class:`akka.pattern.CircuitBreaker` which has the behavior described below.

During normal operation, a circuit breaker is in the `Closed` state.  Exceptions or calls
exceeding the configured `callTimeout` increment a failure counter.  When that failure counter 
reaches a `maxFailures` count, the breaker is tripped into `Open` state.  While in `Open` state 
all calls fail-fast with a :class:`CircuitBreakerOpenException`.  After the configured 
`resetTimeout`, the circuit breaker enters a `Half-Open` state.  In this state, the first call
attempted is allowed through without failing fast.  All other calls fail-fast with an exception
just as in `Open` state.  If the first call succeeds, the breaker is reset back to `Closed` 
state.  Otherwise, the breaker is tripped again into the `Open` state for another full 
`resetTimeout`

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

Here's how a :class:`CircuitBreaker` would be configured for 5 maximum failures, a call timeout of 10 seconds and a reset timeout of 1 minute, first in Scala:

.. code-block:: scala

	import akka.util.duration._  // small d is important here
	import akka.pattern.CircuitBreaker

	// Where system is of type ActorSystem
	val breaker = new CircuitBreaker(system.scheduler, 5, 10.seconds, 1.minute)
	// Be aware that an ExecutionContext must be implicitly provided

Then in Java:

.. code-block:: java

  import akka.util.Duration;
  import akka.pattern.CircuitBreaker;

  ActorSystem system;
  ExecutionContext execCtx;

  // After initialization of system and execCtx
  CircuitBreaker breaker = new CircuitBreaker(execCtx, system.getScheduler(), 5, 
  	Duration.apply("10s"), Duration.apply("1m"))


Here's how the :class:`CircuitBreaker` would be used to protect an asynchronous
call as well as a synchronous one, first in Scala:

.. code-block:: scala

	import akka.dispatch.Future
	import akka.pattern.CircuitBreaker

	val breaker = // ... initialization

	def dangerousCall[T]: T = // implementation of dangerous call

	breaker.withCircuitBreaker(Future(dangerousCall())) // Returns a Future[T] 

	breaker.withSyncCircuitBreaker(dangerousCall()) // Returns a T

Then in Java:

.. code-block:: java

	import akka.dispatch.Future;
	import akka.pattern.CircuitBreaker;

	CircuitBreaker breaker; // needs initialization
	ExecutionContext execCtx; // needs initialization

	private <T> T dangerousCall() {
		// Implementation of dangerous call
	}

  breaker.callWithCircuitBreaker(new Callable<Future<T>>() {
  	public Future<T> call() throws Exception
  	{
  		return Futures.future(new Callable<T>() {
  			public T call() throws Exception
  			{
  				return dangerousCall();
  			}
  		}, execCtx );
  	}
  });

  breaker.callWithSyncCircuitBreaker(new Callable<T>() {
			public T call() throws Exception
			{
				return dangerousCall();
			}
 		}
  });

.. note::

	Using the :class:`CircuitBreaker` companion object's `apply` or `create` methods
	will return a :class:`CircuitBreaker` where futures are executed in the caller's thread.
	This can be useful if the asynchronous :class:`Future` behavior is unnecessary, for
	example invoking a synchronous-only API.