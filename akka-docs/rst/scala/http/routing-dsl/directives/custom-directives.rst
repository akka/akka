.. _Custom Directives:

Custom Directives
=================

Part of the power of akka-http directives comes from the ease with which it’s possible to define
custom directives at differing levels of abstraction.

There are essentially three ways of creating custom directives:

1. By introducing new “labels” for configurations of existing directives
2. By transforming existing directives
3. By writing a directive “from scratch”

Configuration Labeling
______________________
The easiest way to create a custom directive is to simply assign a new name for a certain configuration
of one or more existing directives. In fact, most of the predefined akka-http directives can be considered
named configurations of more low-level directives.

The basic technique is explained in the chapter about Composing Directives, where, for example, a new directive
``getOrPut`` is defined like this:

.. includecode2:: ../../../code/docs/http/scaladsl/server/directives/CustomDirectivesExamplesSpec.scala
   :snippet: labeling

Another example is the :ref:`MethodDirectives` which are simply instances of a preconfigured :ref:`-method-` directive.
The low-level directives that most often form the basis of higher-level “named configuration” directives are grouped
together in the :ref:`BasicDirectives` trait.


Transforming Directives
_______________________

The second option for creating new directives is to transform an existing one using one of the
“transformation methods”, which are defined on the `Directive`__ class, the base class of all “regular” directives.

__ @github@/akka-http/src/main/scala/akka/http/scaladsl/server/Directive.scala

Apart from the combinator operators (``|`` and ``&``) and the case-class extractor (``as[T]``)
there following transformations is also defined on all ``Directive`` instances:

 * :ref:`map/tmap`
 * :ref:`flatMap/tflatMap`
 * :ref:`require/trequire`
 * :ref:`recover/recoverPF`

.. _map/tmap:

map and tmap
------------
If the Directive is a single-value ``Directive``, the ``map`` method allows
for simple transformations:

.. includecode2:: ../../../code/docs/http/scaladsl/server/directives/CustomDirectivesExamplesSpec.scala
   :snippet: map-0

One example of a predefined directive relying on ``map`` is the `optionalHeaderValue`__ directive.

__ @github@/akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala#L67

The tmap modifier has this signature (somewhat simplified)::

    def tmap[R](f: L ⇒ R): Directive[Out]

It can be used to transform the ``Tuple`` of extractions into another ``Tuple``.
The number and/or types of the extractions can be changed arbitrarily. For example
if ``R`` is ``Tuple2[A, B]`` then the result will be a ``Directive[(A, B)]``. Here is a
somewhat contrived example:

.. includecode2:: ../../../code/docs/http/scaladsl/server/directives/CustomDirectivesExamplesSpec.scala
   :snippet: tmap-1



.. _flatMap/tflatMap:

flatMap and tflatMap
--------------------

With map and tmap you can transform the values a directive extracts
but you cannot change the “extracting” nature of the directive.
For example, if you have a directive extracting an ``Int`` you can use map to turn
it into a directive that extracts that ``Int`` and doubles it, but you cannot transform
it into a directive, that doubles all positive ``Int`` values and rejects all others.

In order to do the latter you need ``flatMap`` or ``tflatMap``. The ``tflatMap``
modifier has this signature::

    def tflatMap[R: Tuple](f: L ⇒ Directive[R]): Directive[R]

The given function produces a new directive depending on the Tuple of extractions
of the underlying one. As in the case of :ref:`map/tmap` there is also a single-value
variant called ``flatMap``, which simplifies the operation for Directives only extracting one single value.

Here is the (contrived) example from above, which doubles positive Int values and rejects all others:

.. includecode2:: ../../../code/docs/http/scaladsl/server/directives/CustomDirectivesExamplesSpec.scala
   :snippet: flatMap-0

A common pattern that relies on flatMap is to first extract a value
from the RequestContext with the extract directive and then flatMap with
some kind of filtering logic. For example, this is the implementation
of the method directive:

.. includecode2:: ../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala
   :snippet: method

The explicit type parameter ``[Unit]`` on the flatMap i`s needed in this case
because the result of the flatMap is directly concatenated with the
``cancelAllRejections`` directive, thereby preventing “outside-in”
inference of the type parameter value.

.. _require/trequire:

require and trequire
--------------------

The require modifier transforms a single-extraction directive into a directive
without extractions, which filters the requests according the a predicate function.
All requests, for which the predicate is false are rejected, all others pass unchanged.

The signature of require is this::

     def require(predicate: T ⇒ Boolean, rejections: Rejection*): Directive0

One example of a predefined directive relying on require is the first overload of the host directive:

.. includecode2:: ../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HostDirectives.scala
   :snippet: require-host

You can only call require on single-extraction directives. The trequire modifier is the
more general variant, which takes a predicate of type ``Tuple => Boolean``.
It can therefore also be used on directives with several extractions.


.. _recover/recoverPF:

recover and recoverPF
---------------------

The ``recover`` modifier allows you “catch” rejections produced by the underlying
directive and, instead of rejecting, produce an alternative directive with the same type(s) of extractions.

The signature of recover is this::

     def recover[R >: L: Tuple](recovery: Seq[Rejection] ⇒ Directive[R]): Directive[R] =

In many cases the very similar ``recoverPF`` modifier might be little bit
easier to use since it doesn’t require the handling of all rejections::

    def recoverPF[R >: L: Tuple](
      recovery: PartialFunction[Seq[Rejection], Directive[R]]): Directive[R]


One example of a predefined directive relying ``recoverPF`` is the optionalHeaderValue directive:

.. includecode2:: ../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HeaderDirectives.scala
   :snippet: optional-header



Directives from Scratch
_______________________

The third option for creating custom directives is to do it “from scratch”,
by directly subclassing the Directive class. The Directive is defined like this
(leaving away operators and modifiers):

.. includecode2:: ../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/Directive.scala
   :snippet: basic

It only has one abstract member that you need to implement, the happly method, which creates
the Route the directives presents to the outside from its inner Route building function
(taking the extractions as parameter).

Extractions are kept as a Tuple. Here are a few examples:

A ``Directive[Unit]`` extracts nothing (like the get directive).
Because this type is used quite frequently akka-http defines a type alias for it::

    type Directive0 = Directive[Unit]

A ``Directive[(String)]`` extracts one String value (like the hostName directive). The type alias for it is::

    type Directive1[T] = Directive[Tuple1[T]]

A Directive[(Int, String)] extracts an ``Int`` value and a ``String`` value
(like a ``parameters('a.as[Int], 'b.as[String])`` directive).

Keeping extractions as ``Tuples`` has a lot of advantages, mainly great flexibility
while upholding full type safety and “inferability”. However, the number of times
where you’ll really have to fall back to defining a directive from scratch should
be very small. In fact, if you find yourself in a position where a “from scratch”
directive is your only option, we’d like to hear about it,
so we can provide a higher-level “something” for other users.
