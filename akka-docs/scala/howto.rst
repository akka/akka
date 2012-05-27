
.. _howto-scala:

######################
HowTo: Common Patterns
######################

This section lists common actor patterns which have been found to be useful,
elegant or instructive. Anything is welcome, example topics being message
routing strategies, supervision patterns, restart handling, etc. As a special
bonus, additions to this section are marked with the contributor’s name, and it
would be nice if every Akka user who finds a recurring pattern in his or her
code could share it for the profit of all. Where applicable it might also make
sense to add to the ``akka.pattern`` package for creating an `OTP-like library
<http://www.erlang.org/doc/man_index.html>`_.

Periodic update of a shared read-only resource using Actors
===========================================================

*Contributed by: Konrad Malawski, Adam Warski*

The following pattern may be used in any kind of situation where you want to periodically refresh/update some otherwise read-only shared resource. We'll show an implementation using a Cache as example, but you can easily imagine other situations where this kind of pattern might prove to be useful.

Our cache trait will define a refresh method, which will be called by a so-called "Refresher", and an abstact :class:`AtomicReference` field, which we will use as the holder of our "to be refreshed" data.

.. includecode:: code/docs/actor/ActorSelfRefreshingCache.scala
   :include: refreshable-cache-trait

Using an :class:`AtomicReference` here makes it safe to share the Cache among other threads / actors. The update to the cache's state will happen atomically when the CacheRefresher triggers the :meth:`refresh()` method.

The implementation of the metioned :class:`CacheRefresher` is quite simple. In the preStart method we trigger one initial refresh of the cache. Note that consequent updates will be triggered by the scheduler, or other actors sending a RefreshCache message, not by this actor by itself.

.. includecode:: code/docs/actor/ActorSelfRefreshingCache.scala
   :include: cache-actor

It's important to note that we use Akka's :class:`NonFatal` extractor instead of just :class:`Throwable`, which will extract all but "fatal" exceptions, which would be for example :class:`OutOfMemoryError` and his friends. Other exceptions are wrapped and thrown up again - the reason for wrapping them is tha this way it's easier to define custom SupervisionStrategies, for details on this topic see: :ref:`fault-tolerance-scala`.

The Cache implementation is just a class implementing the :class:`RefreshableCache`, so all it needs to do it fetch the new data when refresh is called, and update the atomic reference:

.. includecode:: code/docs/actor/ActorSelfRefreshingCache.scala
   :include: example-cache

Since we don't want other users of the cache to accidentally trigger refreshes, we introduced the :class:`DataCache` trait, which, which hides the fact that this cache can refresh itself, and provides a nice API for other developers. 

To wire it all together we only have to create the cache and start the Refresher, providing it with some Duration, so it knows how often it should refresh. Remember that you can always send it an extra :class:`RefreshCache` message if you really need to.

.. includecode:: code/docs/actor/ActorSelfRefreshingCache.scala
   :include: cache-actor-setup

Note that the default behaviour when an exception would be thrown in an actor is that it will be restarted - so the CacheRefresher will always try to refresh the cache, even if it fails once for example. For more details about the default Supervision Strategy in Akka see: :ref:`fault-tolerance-scala`. 

Template Pattern
================

*Contributed by: N. N.*

This is an especially nice pattern, since it does even come with some empty example code:

.. includecode:: code/docs/pattern/ScalaTemplate.scala
   :include: all-of-it
   :exclude: uninteresting-stuff

.. note::

   Spread the word: this is the easiest way to get famous!

Please keep this pattern at the end of this file.