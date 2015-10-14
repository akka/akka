.. _-recoverRejectionsWith-:

recoverRejectionsWith
=====================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: recoverRejectionsWith

Description
-----------

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the :ref:`-handleRejections-` directive which provides a nicer DSL for building rejection handlers.

Transforms rejections from the inner route with an ``immutable.Seq[Rejection] ⇒ Future[RouteResult]`` function.

Asynchronous version of :ref:`-recoverRejections-`.

See :ref:`-recoverRejections-` (the synchronous equivalent of this directive) for a detailed description.

.. note::
  To learn more about how and why rejections work read the :ref:`rejections-scala` section of the documentation.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: recoverRejectionsWith
