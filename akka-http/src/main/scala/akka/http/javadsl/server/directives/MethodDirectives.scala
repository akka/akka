/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.function

import akka.http.javadsl.model.HttpMethod
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Route

import akka.http.scaladsl.server.directives.{ MethodDirectives ⇒ D }

abstract class MethodDirectives extends MarshallingDirectives {
  def delete(inner: function.Supplier[Route]): Route = RouteAdapter(
    D.delete { inner.get.delegate })

  def get(inner: function.Supplier[Route]): Route = RouteAdapter(
    D.get { inner.get.delegate })

  def head(inner: function.Supplier[Route]): Route = RouteAdapter(
    D.head { inner.get.delegate })

  def options(inner: function.Supplier[Route]): Route = RouteAdapter(
    D.options { inner.get.delegate })

  def patch(inner: function.Supplier[Route]): Route = RouteAdapter(
    D.patch { inner.get.delegate })

  def post(inner: function.Supplier[Route]): Route = RouteAdapter(
    D.post { inner.get.delegate })

  def put(inner: function.Supplier[Route]): Route = RouteAdapter(
    D.put { inner.get.delegate })

  def extractMethod(inner: function.Function[HttpMethod, Route]) = RouteAdapter(
    D.extractMethod { m ⇒
      inner.apply(m).delegate
    })

  def method(method: HttpMethod, inner: function.Supplier[Route]): Route = RouteAdapter(
    D.method(method) {
      inner.get.delegate
    })
}
