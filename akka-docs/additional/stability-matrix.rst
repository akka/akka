Feature Stability Matrix
========================

Akka is comprised of a number if modules, with different levels of maturity and in different parts of their lifecycle, the matrix below gives you get current stability level of the modules.

Explanation of the different levels of stability
------------------------------------------------

* **Solid** - Proven solid in heavy production usage
* **Stable** - Ready for use in production environment
* **In progress** - Not enough feedback/use to claim it's ready for production use

================================  ============  ============  ============
Feature                           Solid         Stable        In progress
================================  ============  ============  ============
Actors (Scala)                    Solid
Actors (Java)                     Solid
Typed Actors (Scala)              Solid
Typed Actors (Java)               Solid
STM (Scala)                       Solid
STM (Java)                        Solid
Transactors (Scala)               Solid
Transactors (Java)                Solid
Remote Actors (Scala)             Solid
Remote Actors (Java)              Solid
Camel                             Solid
AMQP                              Solid
HTTP                              Solid
Integration Guice                               Stable
Integration Spring                              Stable
Scheduler                         Solid
Redis Pub Sub                                                 In progress
================================  ============  ============  ============
