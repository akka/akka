.. _stream-dynamic-java:

#######################
Dynamic stream handling
#######################

.. _kill-switch-java:

Controlling graph completion with KillSwitch
--------------------------------------------

A ``KillSwitch`` allows the completion of graphs of ``FlowShape`` from the outside. It consists of a flow element that
can be linked to a graph of ``FlowShape`` needing completion control.
The ``KillSwitch`` interface allows to:

* complete the graph(s) via ``shutdown()``
* fail the graph(s) via ``abort(Throwable error)``

After the first call to either ``shutdown`` or ``abort``, all subsequent calls to any of these methods will be ignored.
Graph completion is performed by both

* completing its downstream
* cancelling (in case of ``shutdown``) or failing (in case of ``abort``) its upstream.

A ``KillSwitch`` can control the completion of one or multiple streams, and therefore comes in two different flavours.

.. _unique-kill-switch-java:

UniqueKillSwitch
^^^^^^^^^^^^^^^^

``UniqueKillSwitch`` allows to control the completion of **one** materialized ``Graph`` of ``FlowShape``. Refer to the
below for usage examples.

* **Shutdown**

.. includecode:: ../code/docs/stream/KillSwitchDocTest.java#unique-shutdown

* **Abort**

.. includecode:: ../code/docs/stream/KillSwitchDocTest.java#unique-abort

.. _shared-kill-switch-java:

SharedKillSwitch
^^^^^^^^^^^^^^^^

A ``SharedKillSwitch`` allows to control the completion of an arbitrary number graphs of ``FlowShape``. It can be
materialized multiple times via its ``flow`` method, and all materialized graphs linked to it are controlled by the switch.
Refer to the below for usage examples.

* **Shutdown**

.. includecode:: ../code/docs/stream/KillSwitchDocTest.java#shared-shutdown

* **Abort**

.. includecode:: ../code/docs/stream/KillSwitchDocTest.java#shared-abort

.. note::
   A ``UniqueKillSwitch`` is always a result of a materialization, whilst ``SharedKillSwitch`` needs to be constructed
   before any materialization takes place.

