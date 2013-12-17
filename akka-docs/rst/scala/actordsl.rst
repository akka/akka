.. _actordsl-scala:

################
 Actor DSL
################

The Actor DSL
=============

Simple actors—for example one-off workers or even when trying things out in the
REPL—can be created more concisely using the :class:`Act` trait. The supporting
infrastructure is bundled in the following import:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#import

This import is assumed for all code samples throughout this section. The
implicit actor system serves as :class:`ActorRefFactory` for all examples
below. To define a simple actor, the following is sufficient:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#simple-actor

Here, :meth:`actor` takes the role of either ``system.actorOf`` or
``context.actorOf``, depending on which context it is called in: it takes an
implicit :class:`ActorRefFactory`, which within an actor is available in the
form of the ``implicit val context: ActorContext``. Outside of an actor, you’ll
have to either declare an implicit :class:`ActorSystem`, or you can give the
factory explicitly (see further below).

The two possible ways of issuing a ``context.become`` (replacing or adding the
new behavior) are offered separately to enable a clutter-free notation of
nested receives:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#becomeStacked

Please note that calling ``unbecome`` more often than ``becomeStacked`` results
in the original behavior being installed, which in case of the :class:`Act`
trait is the empty behavior (the outer ``become`` just replaces it during
construction).

Life-cycle management
---------------------

Life-cycle hooks are also exposed as DSL elements (see :ref:`start-hook-scala` and :ref:`stop-hook-scala`), where later invocations of the methods shown below will replace the contents of the respective hooks:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#simple-start-stop

The above is enough if the logical life-cycle of the actor matches the restart
cycles (i.e. ``whenStopping`` is executed before a restart and ``whenStarting``
afterwards). If that is not desired, use the following two hooks (see :ref:`restart-hook-scala`):

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#failing-actor

It is also possible to create nested actors, i.e. grand-children, like this:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#nested-actor

.. note::

  In some cases it will be necessary to explicitly pass the
  :class:`ActorRefFactory` to the :meth:`actor()` method (you will notice when
  the compiler tells you about ambiguous implicits).

The grand-child will be supervised by the child; the supervisor strategy for
this relationship can also be configured using a DSL element (supervision
directives are part of the :class:`Act` trait):

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#supervise-with

Actor with :class:`Stash`
-------------------------

Last but not least there is a little bit of convenience magic built-in, which
detects if the runtime class of the statically given actor subtype extends the
:class:`RequiresMessageQueue` trait via the :class:`Stash` trait (this is a
complicated way of saying that ``new Act with Stash`` would not work because its
runtime erased type is just an anonymous subtype of ``Act``). The purpose is to
automatically use the appropriate deque-based mailbox type required by :class:`Stash`.
If you want to use this magic, simply extend :class:`ActWithStash`:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/actor/ActorDSLSpec.scala#act-with-stash