
.. _stm-scala:

#######################################
 Software Transactional Memory (Scala)
#######################################


Overview of STM
===============

An `STM <http://en.wikipedia.org/wiki/Software_transactional_memory>`_ turns the
Java heap into a transactional data set with begin/commit/rollback
semantics. Very much like a regular database. It implements the first three
letters in `ACID`_; ACI:

* Atomic
* Consistent
* Isolated

.. _ACID: http://en.wikipedia.org/wiki/ACID

Generally, the STM is not needed very often when working with Akka. Some
use-cases (that we can think of) are:

- When you really need composable message flows across many actors updating
  their **internal local** state but need them to do that atomically in one big
  transaction. Might not be often, but when you do need this then you are
  screwed without it.
- When you want to share a datastructure across actors.

The use of STM in Akka is inspired by the concepts and views in `Clojure`_\'s
STM. Please take the time to read `this excellent document`_ about state in
clojure and view `this presentation`_ by Rich Hickey (the genius behind
Clojure).

.. _Clojure: http://clojure.org/
.. _this excellent document: http://clojure.org/state
.. _this presentation: http://www.infoq.com/presentations/Value-Identity-State-Rich-Hickey


Scala STM
=========

The STM supported in Akka is `ScalaSTM`_ which will be soon included in the
Scala standard library.

.. _ScalaSTM: http://nbronson.github.com/scala-stm/

The STM is based on Transactional References (referred to as Refs). Refs are
memory cells, holding an (arbitrary) immutable value, that implement CAS
(Compare-And-Swap) semantics and are managed and enforced by the STM for
coordinated changes across many Refs.


Persistent Datastructures
=========================

Working with immutable collections can sometimes give bad performance due to
extensive copying. Scala provides so-called persistent datastructures which
makes working with immutable collections fast. They are immutable but with
constant time access and modification. They use structural sharing and an insert
or update does not ruin the old structure, hence "persistent". Makes working
with immutable composite types fast. The persistent datastructures currently
consist of a `Map`_ and `Vector`_.

.. _Map: http://www.scala-lang.org/api/current/index.html#scala.collection.immutable.Map
.. _Vector: http://www.scala-lang.org/api/current/index.html#scala.collection.immutable.Vector


Integration with Actors
=======================

In Akka we've also integrated Actors and STM in :ref:`agents-scala` and
:ref:`transactors-scala`.
