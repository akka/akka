---
project.description: How to extend Akka with Akka Extensions.
---
# Classic Akka Extensions

If you want to add features to Akka, there is a very elegant, but powerful mechanism for doing so.
It's called Akka Extensions and is comprised of 2 basic components: an `Extension` and an `ExtensionId`.

Extensions will only be loaded once per `ActorSystem`, which will be managed by Akka.
You can choose to have your Extension loaded on-demand or at `ActorSystem` creation time through the Akka configuration.
Details on how to make that happens are below, in the "Loading from Configuration" section.

@@@ warning

Since an extension is a way to hook into Akka itself, the implementor of the extension needs to
ensure the thread safety of his/her extension.

@@@

## Building an Extension

So let's create a sample extension that lets us count the number of times something has happened.

First, we define what our `Extension` should do:

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/ExtensionDocSpec.scala) { #extension }

Java
:  @@snip [ExtensionDocTest.java](/akka-docs/src/test/java/jdocs/extension/ExtensionDocTest.java) { #imports #extension }

Then we need to create an `ExtensionId` for our extension so we can grab a hold of it.

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/ExtensionDocSpec.scala) { #extensionid }

Java
:  @@snip [ExtensionDocTest.java](/akka-docs/src/test/java/jdocs/extension/ExtensionDocTest.java) { #imports #extensionid }

Wicked! Now all we need to do is to actually use it:

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/ExtensionDocSpec.scala) { #extension-usage }

Java
:  @@snip [ExtensionDocTest.java](/akka-docs/src/test/java/jdocs/extension/ExtensionDocTest.java) { #extension-usage }

Or from inside of an Akka Actor:

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/ExtensionDocSpec.scala) { #extension-usage-actor }

Java
:  @@snip [ExtensionDocTest.java](/akka-docs/src/test/java/jdocs/extension/ExtensionDocTest.java) { #extension-usage-actor }

@@@ div { .group-scala }

You can also hide extension behind traits:

@@snip [ExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/ExtensionDocSpec.scala) { #extension-usage-actor-trait }

@@@

That's all there is to it!

## Loading from Configuration

To be able to load extensions from your Akka configuration you must add FQCNs of implementations of either `ExtensionId` or `ExtensionIdProvider`
in the `akka.extensions` section of the config you provide to your `ActorSystem`.

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/ExtensionDocSpec.scala) { #config }

Java
:   @@@vars
    ```
    akka {
      extensions = ["docs.extension.ExtensionDocTest.CountExtension"]
    }
    ```
    @@@

## Applicability

The sky is the limit!
By the way, did you know that Akka `Cluster`, `Serialization` and other features are implemented as Akka Extensions?

<a id="extending-akka-settings"></a>
### Application specific settings

The @ref:[configuration](general/configuration.md) can be used for application specific settings. A good practice is to place those settings in an Extension.

Sample configuration:

@@snip [SettingsExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/SettingsExtensionDocSpec.scala) { #config }

The `Extension`:

Scala
:  @@snip [SettingsExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/SettingsExtensionDocSpec.scala) { #imports #extension #extensionid }

Java
:  @@snip [SettingsExtensionDocTest.java](/akka-docs/src/test/java/jdocs/extension/SettingsExtensionDocTest.java) { #imports #extension #extensionid }

Use it:

Scala
:  @@snip [SettingsExtensionDocSpec.scala](/akka-docs/src/test/scala/docs/extension/SettingsExtensionDocSpec.scala) { #extension-usage-actor }

Java
:  @@snip [SettingsExtensionDocTest.java](/akka-docs/src/test/java/jdocs/extension/SettingsExtensionDocTest.java) { #extension-usage-actor }

## Library extensions

A third part library may register it's extension for auto-loading on actor system startup by appending it to
`akka.library-extensions` in its `reference.conf`.

```
akka.library-extensions += "docs.extension.ExampleExtension"
```

As there is no way to selectively remove such extensions, it should be used with care and only when there is no case
where the user would ever want it disabled or have specific support for disabling such sub-features. One example where
this could be important is in tests.

@@@ warning

The``akka.library-extensions`` must never be assigned (`= ["Extension"]`) instead of appending as this will break
the library-extension mechanism and make behavior depend on class path ordering.

@@@
