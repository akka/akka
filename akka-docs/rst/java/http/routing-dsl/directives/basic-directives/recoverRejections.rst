.. _-recoverRejections-java-:

recoverRejections
=================
Description
-----------

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the :ref:`-handleRejections-java-` directive which provides a nicer DSL for building rejection handlers.

Transforms rejections from the inner route with a ``Function<Iterable<Rejection>, RouteResult>``.
A ``RouteResult`` is either a ``Complete`` containing the ``HttpResponse`` or a ``Rejected`` containing the
rejections.

.. note::
  To learn more about how and why rejections work read the :ref:`rejections-java` section of the documentation.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
