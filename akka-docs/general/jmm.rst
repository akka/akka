.. _jmm:

Akka and the Java Memory Model
================================

A major benefit of using the Typesafe Stack, including Scala and Akka, is that it simplifies the process of writing
concurrent software.  This article discusses how the Typesafe Stack, and Akka in particular, approaches shared memory
in concurrent applications.

The Java Memory Model
---------------------
Prior to Java 5, the Java Memory Model (JMM) was ill defined. It was possible to get all kinds of strange results when
shared memory was accessed by multiple threads, such as:

* a thread not seeing values written by other threads: a visibility problem
* a thread observing 'impossible' behavior of other threads, caused by instructions not being executed in the order

expected: an instruction reordering problem.

With the implementation of JSR 133 in Java 5, a lot of these issues have been resolved. The JMM is a set of rules based
on the "happens-before" relation, which constrain when one memory access must happen before another, and conversely,
when they are allowed to happen out of order. Two examples of these rules are:

* **The monitor lock rule:** a release of a lock happens before every subsequent acquire of the same lock.
* **The volatile variable rule:** a write of a volatile variable happens before every subsequent read of the same volatile variable

Although the JMM can seem complicated, the specification tries to find a balance between ease of use and the ability to
write performant and scalable concurrent data structures.

Actors and the Java Memory Model
--------------------------------
With the Actors implementation in Akka, there are two ways multiple threads can execute actions on shared memory:

* if a message is sent to an actor (e.g. by another actor). In most cases messages are immutable, but if that message
  is not a properly constructed immutable object, without a "happens before" rule, it would be possible for the receiver
  to see partially initialized data structures and possibly even values out of thin air (longs/doubles).
* if an actor makes changes to its internal state while processing a message, and accesses that state while processing
  another message moments later. It is important to realize that with the actor model you don't get any guarantee that
  the same thread will be executing the same actor for different messages.

To prevent visibility and reordering problems on actors, Akka guarantees the following two "happens before" rules:

*  **The actor send rule:** the send of the message to an actor happens before the receive of that message by the same actor.
*  **The actor subsequent processing rule:** processing of one message happens before processing of the next message by the same actor.

Both rules only apply for the same actor instance and are not valid if different actors are used.

STM and the Java Memory Model
-----------------------------
Akka's Software Transactional Memory (STM) also provides a "happens before" rule:

*  **The transactional reference rule:** a successful write during commit, on an transactional reference, happens before every
   subsequent read of the same transactional reference.

This rule looks a lot like the 'volatile variable' rule from the JMM. Currently the Akka STM only supports deferred writes,
so the actual writing to shared memory is deferred until the transaction commits. Writes during the transaction are placed
in a local buffer (the writeset of the transaction) and are not visible to other transactions. That is why dirty reads are
not possible.

How these rules are realized in Akka is an implementation detail and can change over time, and the exact details could
even depend on the used configuration. But they will build on the other JMM rules like the monitor lock rule or the
volatile variable rule. This means that you, the Akka user, do not need to worry about adding synchronization to provide
such a "happens before" relation, because it is the responsibility of Akka. So you have your hands free to deal with your
business logic, and the Akka framework makes sure that those rules are guaranteed on your behalf.

.. _jmm-shared-state:

Actors and shared mutable state
-------------------------------

Since Akka runs on the JVM there are still some rules to be followed.

* Closing over internal Actor state and exposing it to other threads

.. code-block:: scala

    class MyActor extends Actor {
     var state = ...
     def receive = {
        case _ =>
          //Wrongs

        // Very bad, shared mutable state,
        // will break your application in weird ways
          Future { state = NewState }
          anotherActor ? message onSuccess { r => state = r }

        // Very bad, "sender" changes for every message,
        // shared mutable state bug
          Future { expensiveCalculation(sender) }

          //Rights

        // Completely safe, "self" is OK to close over
        // and it's an ActorRef, which is thread-safe
          Future { expensiveCalculation() } onComplete { f => self ! f.value.get }

        // Completely safe, we close over a fixed value
        // and it's an ActorRef, which is thread-safe
          val currentSender = sender
          Future { expensiveCalculation(currentSender) }
     }
    }

* Messages **should** be immutable, this is to avoid the shared mutable state trap.