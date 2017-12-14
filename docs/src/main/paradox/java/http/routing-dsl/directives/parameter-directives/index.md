# ParameterDirectives

@@toc { depth=1 }

@@@ index

* [parameter](parameter.md)
* [parameters](parameters.md)
* [parameterMap](parameterMap.md)
* [parameterMultiMap](parameterMultiMap.md)
* [parameterSeq](parameterSeq.md)

@@@

<a id="which-parameter-directive"></a>
## When to use which parameter directive?

Usually, you want to use the high-level @ref[parameter](parameter.md) directive. When you need
more low-level access you can use the table below to decide which directive
to use which shows properties of different parameter directives.

|directive                                     | level | ordering | multi|
|----------------------------------------------|-------|----------|------|
|@ref[parameter](parameter.md)                 | high  | no       | no   |
|@ref[parameterMap](parameterMap.md)           | low   | no       | no   |
|@ref[parameterMultiMap](parameterMultiMap.md) | low   | no       | yes  |
|@ref[parameterList](parameterSeq.md)          | low   | yes      | yes  |

level
: high-level parameter directives extract subset of all parameters by name and allow conversions
and automatically report errors if expectations are not met, low-level directives give you
all parameters at once, leaving all further processing to you

ordering
: original ordering from request URL is preserved

multi
: multiple values per parameter name are possible


@@@ note
If you need to extract multiple parameters, apply the `parameter` directive multiple times.
@@@
