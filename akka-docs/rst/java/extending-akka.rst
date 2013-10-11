.. _extending-akka-java:

########################
 Akka Extensions
########################


If you want to add features to Akka, there is a very elegant, but powerful mechanism for doing so.
It's called Akka Extensions and is comprised of 2 basic components: an ``Extension`` and an ``ExtensionId``.

Extensions will only be loaded once per ``ActorSystem``, which will be managed by Akka.
You can choose to have your Extension loaded on-demand or at ``ActorSystem`` creation time through the Akka configuration.
Details on how to make that happens are below, in the "Loading from Configuration" section.

.. warning::

    Since an extension is a way to hook into Akka itself, the implementor of the extension needs to
    ensure the thread safety of his/her extension.


Building an Extension
=====================

So let's create a sample extension that just lets us count the number of times something has happened.

First, we define what our ``Extension`` should do:

.. includecode:: code/docs/extension/ExtensionDocTest.java
   :include: imports

.. includecode:: code/docs/extension/ExtensionDocTest.java
   :include: extension

Then we need to create an ``ExtensionId`` for our extension so we can grab ahold of it.

.. includecode:: code/docs/extension/ExtensionDocTest.java
   :include: imports

.. includecode:: code/docs/extension/ExtensionDocTest.java
   :include: extensionid

Wicked! Now all we need to do is to actually use it:

.. includecode:: code/docs/extension/ExtensionDocTest.java
   :include: extension-usage

Or from inside of an Akka Actor:

.. includecode:: code/docs/extension/ExtensionDocTest.java
   :include: extension-usage-actor

That's all there is to it!

Loading from Configuration
==========================

To be able to load extensions from your Akka configuration you must add FQCNs of implementations of either ``ExtensionId`` or ``ExtensionIdProvider``
in the "akka.extensions" section of the config you provide to your ``ActorSystem``.

::

    akka {
      extensions = ["docs.extension.ExtensionDocTest.CountExtension"]
    }

Applicability
=============

The sky is the limit!
By the way, did you know that Akka's ``Typed Actors``, ``Serialization`` and other features are implemented as Akka Extensions?

.. _extending-akka-java.settings:

Application specific settings
-----------------------------

The :ref:`configuration` can be used for application specific settings. A good practice is to place those settings in an Extension.

Sample configuration:

.. includecode:: ../scala/code/docs/extension/SettingsExtensionDocSpec.scala
   :include: config

The ``Extension``:

.. includecode:: code/docs/extension/SettingsExtensionDocTest.java
   :include: imports

.. includecode:: code/docs/extension/SettingsExtensionDocTest.java
   :include: extension,extensionid

Use it:

.. includecode:: code/docs/extension/SettingsExtensionDocTest.java
   :include: extension-usage-actor

