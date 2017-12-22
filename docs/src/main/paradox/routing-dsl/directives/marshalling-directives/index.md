# Marshalling Directives

Marshalling directives work in conjunction with `akka.http.scaladsl.marshalling` and `akka.http.scaladsl.unmarshalling` to convert
a request entity to a specific type or a type to a response.

See @ref[marshalling](../../../common/marshalling.md) and @ref[unmarshalling](../../../common/unmarshalling.md) for specific
serialization (also known as pickling) guidance.

Marshalling directives usually rely on an in-scope implicit marshaller to handle conversion.  

@@toc { depth=1 }

@@@ index

* [completeWith](completeWith.md)
* [entity](entity.md)
* [handleWith](handleWith.md)

@@@

## Understanding Specific Marshalling Directives

|directive                           | behavior                                                                                                                                          |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
|@ref[completeWith](completeWith.md) | Uses a marshaller for a given type to produce a completion function for an inner route. Used in conjunction with *instanceOf* to format responses.|
|@ref[entity](entity.md)             | Unmarshalls the request entity to the given type and passes it to its inner route.  Used in conjunction with *as* to convert requests to objects. |
|@ref[handleWith](handleWith.md)     | Completes a request with a given function, using an in-scope unmarshaller for an input and in-scope marshaller for the output.                    |
