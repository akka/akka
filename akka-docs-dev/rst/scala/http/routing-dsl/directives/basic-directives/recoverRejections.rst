.. _-recoverRejections-:

recoverRejections
=================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: recoverRejections

Description
-----------

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the :ref:`-handleRejections-` directive which provides a nicer DSL for building rejection handlers.

Transforms rejections from the inner route with an ``immutable.Seq[Rejection] ⇒ RouteResult`` function.
A ``RouteResult`` is either a ``Complete(HttpResponse(...))`` or rejections ``Rejected(rejections)``.

.. note::
  To learn more about how and why rejections work read the :ref:`rejections-scala` section of the documentation.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: recoverRejections
