# Circuit Breaker

## Why are they used?

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
@apidoc[CircuitBreaker] which has the behavior described below.

## What do they do?

* During normal operation, a circuit breaker is in the *Closed* state:

    - Exceptions or calls exceeding the configured *callTimeout* increment a failure counter
    - Successes reset the failure count to zero
    - When the failure counter reaches a *maxFailures* count, the breaker is tripped into *Open* state
  
* While in *Open* state:

    - All calls fail-fast with a @apidoc[CircuitBreakerOpenException]
    - After the configured *resetTimeout*, the circuit breaker enters a *Half-Open* state
  
* In *Half-Open* state:

    - The first call attempted is allowed through without failing fast
    - All other calls fail-fast with an exception just as in *Open* state
    - If the first call succeeds, the breaker is reset back to *Closed* state and the *resetTimeout* is reset
    - If the first call fails, the breaker is tripped again into the *Open* state (as for exponential backoff circuit breaker, the *resetTimeout* is multiplied by the exponential backoff factor)
  
* State transition listeners:

    - Callbacks can be provided for every state entry via @apidoc[onOpen](CircuitBreaker) {scala="#onOpen(callback:=%3EUnit):akka.pattern.CircuitBreaker" java="#addOnOpenListener(java.lang.Runnable)"}, @apidoc[onClose](CircuitBreaker) {scala="#onClose(callback:=%3EUnit):akka.pattern.CircuitBreaker" java="#addOnCloseListener(java.lang.Runnable)"}, and @apidoc[onHalfOpen](CircuitBreaker) {scala="#onHalfOpen(callback:=%3EUnit):akka.pattern.CircuitBreaker" java="#addOnHalfOpenListener(java.lang.Runnable)"}
    - These are executed in the @scaladoc[ExecutionContext](scala.concurrent.ExecutionContext) provided.
  
* Calls result listeners:

    - Callbacks can be used eg. to collect statistics about all invocations or to react on specific call results like success, failures or timeouts.
    - Supported callbacks are: @apidoc[onCallSuccess](CircuitBreaker) {scala="#onCallSuccess(callback:Long=%3EUnit):akka.pattern.CircuitBreaker" java="#addOnCallSuccessListener(java.util.function.Consumer)"}, @apidoc[onCallFailure](CircuitBreaker) {scala="#onCallFailure(callback:Long=%3EUnit):akka.pattern.CircuitBreaker" java="#addOnCallFailureListener(java.util.function.Consumer)"}, @apidoc[onCallTimeout](CircuitBreaker) {scala="#onCallTimeout(callback:Long=%3EUnit):akka.pattern.CircuitBreaker" java="#addOnCallTimeoutListener(java.util.function.Consumer)"}, @apidoc[onCallBreakerOpen](CircuitBreaker) {scala="#onCallBreakerOpen(callback:=%3EUnit):akka.pattern.CircuitBreaker" java="#addOnCallBreakerOpenListener(java.lang.Runnable)"}.
    - These are executed in the @scaladoc[ExecutionContext](scala.concurrent.ExecutionContext) provided.
   

![circuit-breaker-states.png](../images/circuit-breaker-states.png)

## Examples

### Initialization

Here's how a @apidoc[CircuitBreaker] would be configured for:

* 5 maximum failures
* a call timeout of 10 seconds
* a reset timeout of 1 minute


Scala
:  @@snip [CircuitBreakerDocSpec.scala](/akka-docs/src/test/scala/docs/circuitbreaker/CircuitBreakerDocSpec.scala) { #imports1 #circuit-breaker-initialization }

Java
:  @@snip [DangerousJavaActor.java](/akka-docs/src/test/java/jdocs/circuitbreaker/DangerousJavaActor.java) { #imports1 #circuit-breaker-initialization }

### Future & Synchronous based API

Once a circuit breaker actor has been initialized, interacting with that actor is done by either using the Future based API or the synchronous API. Both of these APIs are considered `Call Protection` because whether synchronously or asynchronously, the purpose of the circuit breaker is to protect your system from cascading failures while making a call to another service. In the future based API, we use the @scala[@scaladoc[withCircuitBreaker](akka.pattern.CircuitBreaker#withCircuitBreaker[T](body:=%3Escala.concurrent.Future[T]):scala.concurrent.Future[T])]@java[@javadoc[callWithCircuitBreakerCS](akka.pattern.CircuitBreaker#callWithCircuitBreakerCS(java.util.concurrent.Callable))] which takes an asynchronous method (some method wrapped in a @scala[@scaladoc[Future](scala.concurrent.Future)]@java[@javadoc[CompletionState](java.util.concurrent.CompletionStage)]), for instance a call to retrieve data from a database, and we pipe the result back to the sender. If for some reason the database in this example isn't responding, or there is another issue, the circuit breaker will open and stop trying to hit the database again and again until the timeout is over.

The Synchronous API would also wrap your call with the circuit breaker logic, however, it uses the @scala[@scaladoc[withSyncCircuitBreaker](akka.pattern.CircuitBreaker#withSyncCircuitBreaker[T](body:=%3ET):T)]@java[@javadoc[callWithSyncCircuitBreaker](akka.pattern.CircuitBreaker#callWithSyncCircuitBreaker(java.util.concurrent.Callable))] and receives a method that is not wrapped in a @scala[@scaladoc[Future](scala.concurrent.Future)]@java[@javadoc[CompletionState](java.util.concurrent.CompletionStage)].

Scala
: @@snip [CircuitBreakerDocSpec.scala](/akka-docs/src/test/scala/docs/circuitbreaker/CircuitBreakerDocSpec.scala) { #circuit-breaker-usage }

Java
:  @@snip [DangerousJavaActor.java](/akka-docs/src/test/java/jdocs/circuitbreaker/DangerousJavaActor.java) { #circuit-breaker-usage }

@@@ note

Using the @scala[@apidoc[CircuitBreaker](CircuitBreaker$)'s companion object @scaladoc[apply](akka.pattern.CircuitBreaker$#apply(scheduler:akka.actor.Scheduler,maxFailures:Int,callTimeout:scala.concurrent.duration.FiniteDuration,resetTimeout:scala.concurrent.duration.FiniteDuration):akka.pattern.CircuitBreaker)]@java[@javadoc[CircuitBreaker.create](akka.pattern.CircuitBreaker#create(akka.actor.Scheduler,int,java.time.Duration,java.time.Duration))] method
will return a @apidoc[CircuitBreaker] where callbacks are executed in the caller's thread.
This can be useful if the asynchronous @scala[@scaladoc[Future](scala.concurrent.Future)]@java[@javadoc[CompletionState](java.util.concurrent.CompletionStage)] behavior is unnecessary, for
example invoking a synchronous-only API.

@@@

### Control failure count explicitly

By default, the circuit breaker treats @javadoc[Exception](java.lang.Exception) as failure in synchronized API, or failed @scala[@scaladoc[Future](scala.concurrent.Future)]@java[@javadoc[CompletionState](java.util.concurrent.CompletionStage)] as failure in future based API.
On failure, the failure count will increment. If the failure count reaches the *maxFailures*, the circuit breaker will be opened.
However, some applications may require certain exceptions to not increase the failure count.
In other cases one may want to increase the failure count even if the call succeeded.
Akka circuit breaker provides a way to achieve such use cases: @scala[@scaladoc[withCircuitBreaker](akka.pattern.CircuitBreaker#withCircuitBreaker[T](body:=%3Escala.concurrent.Future[T],defineFailureFn:scala.util.Try[T]=%3EBoolean):scala.concurrent.Future[T]) and @scaladoc[withSyncCircuitBreaker](akka.pattern.CircuitBreaker#withSyncCircuitBreaker[T](body:=%3ET,defineFailureFn:scala.util.Try[T]=%3EBoolean):T)]@java[@javadoc[callWithCircuitBreaker](akka.pattern.CircuitBreaker#callWithCircuitBreaker(java.util.concurrent.Callable,java.util.function.BiFunction)), @javadoc[callWithSyncCircuitBreaker](akka.pattern.CircuitBreaker#callWithSyncCircuitBreaker(java.util.concurrent.Callable,java.util.function.BiFunction)) and @javadoc[callWithCircuitBreakerCS](akka.pattern.CircuitBreaker#callWithCircuitBreakerCS(java.util.concurrent.Callable,java.util.function.BiFunction))].

All methods above accept an argument `defineFailureFn`

Type of `defineFailureFn`: @scala[@scaladoc[Try[T]](scala.util.Try) => @scaladoc[Boolean](scala.Boolean)]@java[@javadoc[BiFunction](java.util.function.BiFunction)[@javadoc[Optional[T]](java.util.Optional), @javadoc[Optional](java.util.Optional)[@javadoc[Throwable](java.lang.Throwable)], @javadoc[Boolean](java.lang.Boolean)]]

@scala[This is a function which takes in a @scaladoc[Try[T]](scala.util.Try) and returns a @scaladoc[Boolean](scala.Boolean). The @scaladoc[Try[T]](scala.util.Try) correspond to the @scaladoc[Future[T]](scala.concurrent.Future) of the protected call.]
@java[The response of a protected call is modelled using @javadoc[Optional[T]](java.util.Optional) for a successful return value and @javadoc[Optional](java.util.Optional)[@javadoc[Throwable](java.lang.Throwable)] for exceptions.] This function should return `true` if the call should increase failure count, else false.

Scala
:  @@snip [CircuitBreakerDocSpec.scala](/akka-docs/src/test/scala/docs/circuitbreaker/CircuitBreakerDocSpec.scala) { #even-no-as-failure }

Java
:  @@snip [EvenNoFailureJavaExample.java](/akka-docs/src/test/java/jdocs/circuitbreaker/EvenNoFailureJavaExample.java) { #even-no-as-failure }

### Low level API

The low-level API allows you to describe the behavior of the @apidoc[CircuitBreaker](CircuitBreaker) in detail, including deciding what to return to the calling @apidoc[Actor](Actor) in case of success or failure. This is especially useful when expecting the remote call to send a reply.
@apidoc[CircuitBreaker](CircuitBreaker) doesn't support `Tell Protection` (protecting against calls that expect a reply) natively at the moment.
Thus you need to use the low-level power-user APIs, @apidoc[succeed](CircuitBreaker) {scala="#succeed():Unit" java="#succeed()"}  and  @apidoc[fail](CircuitBreaker) {scala="#fail():Unit" java="#fail()"} methods, as well as @apidoc[isClosed](CircuitBreaker) {scala="#isClosed:Boolean" java="#isClosed()"}, @apidoc[isOpen](CircuitBreaker) {scala="#isOpen:Boolean" java="#isOpen()"}, @apidoc[isHalfOpen](CircuitBreaker) {scala="#isHalfOpen:Boolean" java="#isHalfOpen()"} to implement it.

As can be seen in the examples below, a `Tell Protection` pattern could be implemented by using the @apidoc[succeed](CircuitBreaker) {scala="#succeed():Unit" java="#succeed()"}  and  @apidoc[fail](CircuitBreaker) {scala="#fail():Unit" java="#fail()"} methods, which would count towards the @apidoc[CircuitBreaker](CircuitBreaker) counts. 
In the example, a call is made to the remote service if the @apidoc[breaker.isClosed](CircuitBreaker) {scala="#isClosed:Boolean" java="#isClosed()"}. 
Once a response is received, the @apidoc[succeed](CircuitBreaker) {scala="#succeed():Unit" java="#succeed()"} method is invoked, which tells the @apidoc[CircuitBreaker](CircuitBreaker) to keep the breaker closed. 
On the other hand, if an error or timeout is received we trigger a @apidoc[fail](CircuitBreaker) {scala="#fail():Unit" java="#fail()"}, and the breaker accrues this failure towards its count for opening the breaker.

@@@ note

The below example doesn't make a remote call when the state is *HalfOpen*. Using the power-user APIs, it is your responsibility to judge when to make remote calls in *HalfOpen*.

@@@

Scala
:  @@snip [CircuitBreakerDocSpec.scala](/akka-docs/src/test/scala/docs/circuitbreaker/CircuitBreakerDocSpec.scala) { #circuit-breaker-tell-pattern }

Java
:  @@snip [TellPatternJavaActor.java](/akka-docs/src/test/java/jdocs/circuitbreaker/TellPatternJavaActor.java) { #circuit-breaker-tell-pattern }
