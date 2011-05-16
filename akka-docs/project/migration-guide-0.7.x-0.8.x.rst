Migration Guide 0.7.x to 0.8.x
==============================

This is a case-by-case migration guide from Akka 0.7.x (on Scala 2.7.7) to Akka 0.8.x (on Scala 2.8.x)
------------------------------------------------------------------------------------------------------

Cases:
------

Actor.send is removed and replaced in full with Actor.!
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: scala

  myActor send "test"

becomes

.. code-block:: scala

  myActor ! "test"

Actor.! now has it's implicit sender defaulted to None
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: scala

  def !(message: Any)(implicit sender: Option[Actor] = None)

"import Actor.Sender.Self" has been removed because it's not needed anymore
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Remove

.. code-block:: scala

  import Actor.Sender.Self

Actor.spawn now uses manifests instead of concrete class types
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: scala

  val someActor = spawn(classOf[MyActor])

becomes

.. code-block:: scala

  val someActor = spawn[MyActor]

Actor.spawnRemote now uses manifests instead of concrete class types
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: scala

  val someActor = spawnRemote(classOf[MyActor],"somehost",1337)

becomes

.. code-block:: scala

  val someActor = spawnRemote[MyActor]("somehost",1337)

Actor.spawnLink now uses manifests instead of concrete class types
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: scala

  val someActor = spawnLink(classOf[MyActor])

becomes

.. code-block:: scala

  val someActor = spawnLink[MyActor]

Actor.spawnLinkRemote now uses manifests instead of concrete class types
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: scala

  val someActor = spawnLinkRemote(classOf[MyActor],"somehost",1337)

becomes

.. code-block:: scala

  val someActor = spawnLinkRemote[MyActor]("somehost",1337)

**Transaction.atomic and friends are moved into Transaction.Local._ and Transaction.Global._**
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

We now make a difference between transaction management that are local within a thread and global across many threads (and actors).
