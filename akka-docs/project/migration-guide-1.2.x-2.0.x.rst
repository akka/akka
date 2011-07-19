.. _migration-2.0:

################################
 Migration Guide 1.2.x to 2.0.x
################################

Actors
======

The 2.0 release contains several new features which require source-level
changes in client code. This API cleanup is planned to be the last one for a
significant amount of time.

Lifecycle Callbacks
-------------------

The :meth:`preRestart(cause: Throwable)` method has been replaced by
:meth:`preRestart(cause: Throwable, lastMessage: Any)`, hence you must insert
the second argument in all overriding methods. The good news is that any missed
actor will not compile without error.
