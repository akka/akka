<a id="parameterdirectives"></a>
# ParameterDirectives

@@toc { depth=1 }

@@@ index

* [parameter](parameter.md)
* [parameterMap](parameterMap.md)
* [parameterMultiMap](parameterMultiMap.md)
* [parameters](parameters.md)
* [parameterSeq](parameterSeq.md)

@@@

<a id="which-parameter-directive"></a>
## When to use which parameter directive?

Usually, you want to use the high-level @ref[parameters-scala](parameters.md#parameters-scala) directive. When you need
more low-level access you can use the table below to decide which directive
to use which shows properties of different parameter directives.

|directive                                                       | level | ordering | multi|
|----------------------------------------------------------------|-------|----------|------|
|@ref[parameter](parameter.md#parameter)                         | high | no  | no |
|@ref[parameters-scala](parameters.md#parameters-scala)          | high | no  | yes|
|@ref[parameterMap](parameterMap.md#parametermap)                | low  | no  | no |
|@ref[parameterMultiMap](parameterMultiMap.md#parametermultimap) | low  | no  | yes|
|@ref[parameterSeq](parameterSeq.md#parameterseq)                | low  | yes | yes|

level
: high-level parameter directives extract subset of all parameters by name and allow conversions
and automatically report errors if expectations are not met, low-level directives give you
all parameters at once, leaving all further processing to you

ordering
: original ordering from request URL is preserved

multi
: multiple values per parameter name are possible
