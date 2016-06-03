.. _stream-dynamic-scala:

#######################
Dynamic stream handling
#######################

.. _kill-switch-scala:

Controlling graph completion with KillSwitch
--------------------------------------------

A ``KillSwitch`` allows the completion of graphs of ``FlowShape`` from the outside. It consists of a flow element that
can be linked to a graph of ``FlowShape`` needing completion control.
The ``KillSwitch`` trait allows to complete or fail the graph(s).

.. includecode:: ../../../../akka-stream/src/main/scala/akka/stream/KillSwitch.scala
   :include: kill-switch

After the first call to either ``shutdown`` and ``abort``, all subsequent calls to any of these methods will be ignored.
Graph completion is performed by both

* completing its downstream
* cancelling (in case of ``shutdown``) or failing (in case of ``abort``) its upstream.

A ``KillSwitch`` can control the completion of one or multiple streams, and therefore comes in two different flavours.

.. _unique-kill-switch-scala:

UniqueKillSwitch
^^^^^^^^^^^^^^^^

``UniqueKillSwitch`` allows to control the completion of **one** materialized ``Graph`` of ``FlowShape``. Refer to the
below for usage examples.

* **Shutdown**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#unique-shutdown

* **Abort**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#unique-abort

.. _shared-kill-switch-scala:

SharedKillSwitch
^^^^^^^^^^^^^^^^

A ``SharedKillSwitch`` allows to control the completion of an arbitrary number graphs of ``FlowShape``. It can be
materialized multiple times via its ``flow`` method, and all materialized graphs linked to it are controlled by the switch.
Refer to the below for usage examples.

* **Shutdown**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#shared-shutdown

* **Abort**

.. includecode:: ../code/docs/stream/KillSwitchDocSpec.scala#shared-abort

.. note::
   A ``UniqueKillSwitch`` is always a result of a materialization, whilst ``SharedKillSwitch`` needs to be constructed
   before any materialization takes place.

