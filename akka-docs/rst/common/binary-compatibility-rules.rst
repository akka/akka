.. _BinCompatRules:

Binary Compatibility Rules
##########################

Akka maintains and verifies *backwards binary compatibility* across versions of modules.

In the rest of this document whenever *binary compatibility* is mentioned "*backwards binary compatibility*" is meant
(as opposed to forward compatibility).

This means that the new JARs are a drop-in replacement for the old one 
(but not the other way around) as long as your build does not enable the inliner (Scala-only restriction).

Binary compatibility rules explained
====================================
Binary compatibility is maintained between:

- **minor** and **patch** versions - please note that the meaning of "minor" has shifted to be more restrictive with Akka ``2.4.0``, read :ref:`24versioningChange` for details.

Binary compatibility is **NOT** maintained between:

- **major** versions
- any versions of **experimental** modules – read :ref:`meaning-of-experimental` for details
- a few notable exclusions explained below

Specific examples (please read :ref:`24versioningChange` to understand the difference in "before 2.4 era" and "after 2.4 era")::

  # [epoch.major.minor] era
  OK:  2.2.0 --> 2.2.1 --> ... --> 2.2.x
  NO:  2.2.y --x 2.3.y
  OK:  2.3.0 --> 2.3.1 --> ... --> 2.3.x
  OK:  2.3.x --> 2.4.x (special case, migration to new versioning scheme)
  # [major.minor.path] era
  OK:  2.4.0 --> 2.5.x
  OK:  2.5.0 --> 2.6.x
  NO:  2.x.y --x 3.x.y
  OK:  3.0.0 --> 3.0.1 --> ... --> 3.0.n
  OK:  3.0.n --> 3.1.0 --> ... --> 3.1.n
  OK:  3.1.n --> 3.2.0 ...
       ...

Cases where binary compatibility is not retained
------------------------------------------------
Some modules are excluded from the binary compatibility guarantees, such as:

  - ``*-testkit`` modules - since these are to be used only in tests, which usually are re-compiled and run on demand
  - ``*-tck`` modules     - since they may want to add new tests (or force configuring something), in order to discover possible 
                            failures in an existing implementation that the TCK is supposed to be testing. 
                            Compatibility here is not *guaranteed*, however it is attempted to make the upgrade prosess as smooth as possible.
  - all :ref:`experimental <meaning-of-experimental>` modules - which by definition are subject to rapid iteration and change. Read more about them in :ref:`meaning-of-experimental`

.. _24versioningChange:

Change in versioning scheme, stronger compatibility since 2.4
=============================================================
Since the release of Akka ``2.4.0`` a new versioning scheme is in effect.

Historically, Akka has been following the Java or Scala style of versioning where as the first number would mean "**epoch**",
the second one would mean **major**, and third be the **minor**, thus: ``epoch.major.minor`` (versioning scheme followed until and during ``2.3.x``).

**Currently**, since Akka ``2.4.0``, the new versioning applies which is closer to semantic versioning many have come to expect, 
in which the version number is deciphered as ``major.minor.patch``. This also means that Akka ``2.5.x`` is binary compatible with the ``2.4`` series releases (with the exception of experimental APIs of course).

In addition to that, Akka ``2.4.x`` has been made binary compatible with the ``2.3.x`` series,
so there is no reason to remain on Akka 2.3.x, since upgrading is completely compatible 
(and many issues have been fixed ever since).

Mixed versioning is not allowed
===============================

Modules that are released together under the Akka project are intended to be upgraded together.
For example, it is not legal to mix Akka Actor ``2.4.2`` with Akka Cluster ``2.4.5`` even though
"Akka ``2.4.2``" and "Akka ``2.4.5``" *are* binary compatible. 

This is because modules may assume internals changes across module boundaries, for example some feature
in Clustering may have required an internals change in Actor, however it is not public API, 
thus such change is considered safe.

.. note::
  We recommend keeping an ``akkaVersion`` variable in your build file, and re-use it for all 
  included modules, so when you upgrade you can simply change it in this one place.

.. _meaning-of-experimental:

The meaning of "experimental"
=============================
**Experimental** is a keyword used in module descriptions as well as their artifact names,
in order to signify that the API that they contain is subject to change without any prior warning.

Experimental modules are are not covered by Lightbend's Commercial Support, unless specifically stated otherwise.
The purpose of releasing them early, as 
experimental, is to make them easily available and improve based on 
feedback, or even discover that the module wasn't useful.

An experimental module doesn't have to obey the rule of staying binary
compatible between micro releases. Breaking API changes may be introduced
in minor releases without notice as we refine and simplify based on your
feedback. An experimental module may be dropped in minor releases without 
prior deprecation.

Best effort migration guides may be provided, but this is decided on a case-by-case basis for **experimental** modules.

API stability annotations and comments
======================================

Akka gives a very strong binary compatibility promise to end-users. However some parts of Akka are excluded 
from these rules, for example internal or known evolving APIs may be marked as such and shipped as part of 
an overall stable module. As general rule any breakage is avoided and handled via deprecation and additional method,
however certain APIs which are known to not yet be fully frozen (or are fully internal) are marked as such and subject 
to change at any time (even if best-effort is taken to keep them compatible).

The INTERNAL API and `@InternalAPI` marker
------------------------------------------
When browsing the source code and/or looking for methods available to be called, especially from Java which does not
have as rich of an access protection system as Scala has, you may sometimes find methods or classes annotated with
the ``/** INTERNAL API */`` comment or the ``@akka.annotation.InternalApi`` annotation. 

No compatibility guarantees are given about these classes, they may change or even disapear in minor versions, 
and user code is not supposed to be calling (or even touching) them.

Side-note on JVM representation details of the Scala ``private[akka]`` pattern that Akka is using extensively in 
it's internals: Such methods or classes, which act as "accessible only from the given package" in Scala, are compiled
down to ``public`` (!) in raw Java bytecode, and the access restriction, that Scala understands is carried along 
as metadata stored in the classfile. Thus, such methods are safely guarded from being accessed from Scala,
however Java users will not be warned about this fact by the ``javac`` compiler. Please be aware of this and do not call
into Internal APIs, as they are subject to change without any warning.

The ``@DoNotInherit`` and ``@ApiMayChange`` markers
---------------------------------------------------

In addition to the special internal API marker two annotations exist in Akka and specifically address the following use cases:

- ``@ApiMayChange`` – which marks APIs which are known to be not fully stable yet. For example, when while introducing 
  "new" Java 8 APIs into existing stable modules, these APIs may be marked with this annotation to signal that they are
  not frozen yet. Please use such methods and classes with care, however if you see such APIs that is the best point in 
  time to try them out and provide feedback (e.g. using the akka-user mailing list, github issues or gitter) before they 
  are frozen as fully stable API.
- ``@DoNotInherit`` – which marks APIs that are designed under an closed-world assumption, and thus must not be 
  extended outside Akka itself (or such code will risk facing binary incompatibilities). E.g. an interface may be 
  marked using this annotation, and while the type is public, it is not meant for extension by user-code. This allows 
  adding new methods to these interfaces without risking to break client code. Examples of such API are the ``FlowOps`` 
  trait or the Akka HTTP domain model.

Please note that a best-effort approach is always taken when having to change APIs and breakage is avoided as much as 
possible, however these markers allow to experiment, gather feedback and stabilize the best possible APIs we could build.

Binary Compatibility Checking Toolchain
=======================================
Akka uses the Lightbend maintained `Migration Manager <https://github.com/typesafehub/migration-manager>`_, 
called ``MiMa`` for short, for enforcing binary compatibility is kept where it was promised.

All Pull Requests must pass MiMa validation (which happens automatically), and if failures are detected,
manual exception overrides may be put in place if the change happened to be in an Internal API for example.

Serialization compatibility across Scala versions
=================================================

Scala does not maintain serialization compatibility across major versions. This means that if Java serialization is used
there is no guarantee objects can be cleanly deserialized if serialized with a different version of Scala.

The internal Akka Protobuf serializers that can be enabled explicitly with ``enable-additional-serialization-bindings``
or implicitly with ``akka.actor.allow-java-serialization = off`` (which is preferable from a security standpoint)
does not suffer from this problem.
