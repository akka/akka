Akka and the Java Memory Model
================================

Prior to Java 5, the Java Memory Model (JMM) was broken. It was possible to get all kinds of strange results like unpredictable merged writes made by concurrent executing threads, unexpected reordering of instructions, and even final fields were not guaranteed to be final. With Java 5 and JSR-133, the Java Memory Model is clearly specified. This specification makes it possible to write code that performs, but doesn't cause concurrency problems. The Java Memory Model is specified in 'happens before'-rules, e.g.:

* **monitor lock rule**: a release of a lock happens before every subsequent acquire of the same lock.
* **volatile variable rule**: a write of a volatile variable happens before every subsequent read of the same volatile variable

The 'happens before'-rules clearly specify which visibility guarantees are provided on memory and which re-orderings are allowed. Without these rules it would not be possible to write concurrent and performant code in Java.

Actors and the Java Memory Model
--------------------------------

With the Actors implementation in Akka, there are 2 ways multiple threads can execute actions on shared memory over time:

* if a message is send to an actor (e.g. by another actor). In most cases messages are immutable, but if that message is not a properly constructed immutable object, without happens before rules, the system still could be subject to instruction re-orderings and visibility problems (so a possible source of concurrency errors).
* if an actor makes changes to its internal state in one 'receive' method and access that state while processing another message. With the actors model you don't get any guarantee that the same thread will be executing the same actor for different messages. Without a happens before relation between these actions, there could be another source of concurrency errors.

To solve the 2 problems above, Akka adds the following 2 'happens before'-rules to the JMM:

* **the actor send rule**: where the send of the message to an actor happens before the receive of the **same** actor.
* **the actor subsequent processing rule**: where processing of one message happens before processing of the next message by the **same** actor.

Both rules only apply for the same actor instance and are not valid if different actors are used.

STM and the Java Memory Model
-----------------------------

The Akka STM also provides a happens before rule called:

* **the transaction rule**: a commit on a transaction happens before every subsequent start of a transaction where there is at least 1 shared reference.

How these rules are realized in Akka, is an implementation detail and can change over time (the exact details could even depend on the used configuration) but they will lift on the other JMM rules like the monitor lock rule or the volatile variable rule. Essentially this means that you, the Akka user, do not need to worry about adding synchronization to provide such a happens before relation, because it is the responsibility of Akka. So you have your hands free to deal with your problems and not that of the framework.



