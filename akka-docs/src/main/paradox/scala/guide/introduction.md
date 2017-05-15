# Introduction to Akka

Welcome to Akka, a set of open-source libraries for designing scalable, resilient systems that
span processor cores and networks. Akka allows you to focus on meeting business needs instead
of writing low-level code to provide reliable behavior, fault tolerance, and high performance.

Common practices and programming models do not address important challenges inherent in designing systems
for modern computer architectures. To be successful, distributed systems must cope in an environment where components
crash without responding, messages get lost without a trace on the wire, and network latency fluctuates.
These problems occur regularly in carefully managed intra-datacenter environments - even more so in virtualized
architectures.

To deal with these realities, Akka provides:

 * Multi-threaded behavior without the use of low-level concurrency constructs like
   atomics or locks. You do not even need to think about memory visibility issues.
 * Transparent remote communication between systems and their components. You do
   not need to write or maintain difficult networking code.
 * A clustered, high-availability architecture that is elastic, scales in or out, on demand.

All of these features are available through a uniform programming model: Akka exploits the actor model
to provide a level of abstraction that makes it easier to write correct concurrent, parallel and distributed systems.
The actor model spans the set of Akka libraries, providing you with a consistent way of understanding and using them.
Thus, Akka offers a depth of integration that you cannot achieve by picking libraries to solve individual problems and
trying to piece them together.

By learning Akka and its actor model, you will gain access to a vast and deep set of tools that solve difficult
distributed/parallel systems problems in a uniform programming model where everything fits together tightly and
efficiently.

## What is the Actor Model?

The characteristics of today's computing environments are vastly different from the ones in use when the programming
models of yesterday were conceived. Actors were invented decades ago by @extref[Carl Hewitt](wikipedia:Carl_Hewitt#Actor_model).
But relatively recently, their applicability to the challenges of modern computing systems has been recognized and
proved to be effective.

The actor model provides an abstraction that allows you to think about your code in terms of communication, not unlike
people in a large organization. The basic characteristic of actors is that they model the world as stateful entities
communicating with each other by explicit message passing.

As computational entities, actors have these characteristics:

* They communicate with asynchronous messaging instead of method calls
* They manage their own state
* When responding to a message, they can:
    * Create other (child) actors
    * Send messages to other actors
    * Stop (child) actors or themselves
