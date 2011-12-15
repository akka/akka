.. _extending-akka:

Akka Extensions
===============

.. sidebar:: Contents

   .. contents:: :local:


Building an Extension
---------------------

So let's create a sample extension that just lets us count the number of times something has happened.

First, we define what our ``Extension`` should do:

.. includecode:: code/akka/docs/extension/ExtensionDocSpec.scala
   :include: imports,extension

Then we need to create an ``ExtensionId`` for our extension so we can grab ahold of it.

.. includecode:: code/akka/docs/extension/ExtensionDocSpec.scala
   :include: imports,extensionid 

Wicked! Now all we need to do is to actually use it:

.. includecode:: code/akka/docs/extension/ExtensionDocSpec.scala
   :include: extension-usage

Or from inside of an Akka Actor:

.. includecode:: code/akka/docs/extension/ExtensionDocSpec.scala
   :include: extension-usage-actor

You can also hide extension behind traits:

.. includecode:: code/akka/docs/extension/ExtensionDocSpec.scala
   :include: extension-usage-actor-trait

That's all there is to it!

Loading from Configuration
--------------------------

To be able to load extensions from your Akka configuration you must add FQCNs of implementations of either ``ExtensionId`` or ``ExtensionIdProvider``
in the "akka.extensions" section of the config you provide to your ``ActorSystem``.

Applicability
-------------

The sky is the limit!
By the way, did you know that Akka's ``Typed Actors``, ``Serialization`` and other features are implemented as Akka Extensions?