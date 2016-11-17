# Predefined Directives (by trait)

All predefined directives are organized into traits that form one part of the overarching `Directives` trait.

<a id="request-directives-java"></a>
## Directives filtering or extracting from the request

@ref[MethodDirectives](method-directives/index.md#methoddirectives-java)
:   Filter and extract based on the request method.

@ref[HeaderDirectives](header-directives/index.md#headerdirectives-java)
:   Filter and extract based on request headers.

@ref[PathDirectives](path-directives/index.md#pathdirectives-java)
:   Filter and extract from the request URI path.

@ref[HostDirectives](host-directives/index.md#hostdirectives-java)
:   Filter and extract based on the target host.

@ref[ParameterDirectives](parameter-directives/index.md#parameterdirectives-java), @ref[FormFieldDirectives](form-field-directives/index.md#formfielddirectives-java)
:   Filter and extract based on query parameters or form fields (of Content-Type `application/x-www-form-urlencoded` or `multipart/form-data`).

@ref[CodingDirectives](coding-directives/index.md#codingdirectives-java)
:   Filter and decode compressed request content.

@ref[Marshalling Directives](marshalling-directives/index.md#marshallingdirectives-java)
:   Extract the request entity.

@ref[SchemeDirectives](scheme-directives/index.md#schemedirectives-java)
:   Filter and extract based on the request scheme.

@ref[SecurityDirectives](security-directives/index.md#securitydirectives-java)
:   Handle authentication data from the request.

@ref[CookieDirectives](cookie-directives/index.md#cookiedirectives-java)
:   Filter and extract cookies.

@ref[BasicDirectives](basic-directives/index.md#basicdirectives-java) and @ref[MiscDirectives](misc-directives/index.md#miscdirectives-java)
:   Directives handling request properties.

@ref[FileUploadDirectives](file-upload-directives/index.md#fileuploaddirectives-java)
:   Handle file uploads.

<a id="response-directives-java"></a>
## Directives creating or transforming the response

@ref[CacheConditionDirectives](cache-condition-directives/index.md#cacheconditiondirectives-java)
:   Support for conditional requests (`304 Not Modified` responses).

@ref[CookieDirectives](cookie-directives/index.md#cookiedirectives-java)
:   Set, modify, or delete cookies.

@ref[CodingDirectives](coding-directives/index.md#codingdirectives-java)
:   Compress responses.

@ref[FileAndResourceDirectives](file-and-resource-directives/index.md#fileandresourcedirectives-java)
:   Deliver responses from files and resources.

@ref[RangeDirectives](range-directives/index.md#rangedirectives-java)
:   Support for range requests (`206 Partial Content` responses).

@ref[RespondWithDirectives](respond-with-directives/index.md#respondwithdirectives-java)
:   Change response properties.

@ref[RouteDirectives](route-directives/index.md#routedirectives-java)
:   Complete or reject a request with a response.

@ref[BasicDirectives](basic-directives/index.md#basicdirectives-java) and @ref[MiscDirectives](misc-directives/index.md#miscdirectives-java)
:   Directives handling or transforming response properties.

@ref[TimeoutDirectives](timeout-directives/index.md#timeoutdirectives-java)
:   Configure request timeouts and automatic timeout responses.

## List of predefined directives by trait

@@toc { depth=1 }

@@@ index

* [basic-directives/index](basic-directives/index.md)
* [cache-condition-directives/index](cache-condition-directives/index.md)
* [coding-directives/index](coding-directives/index.md)
* [cookie-directives/index](cookie-directives/index.md)
* [debugging-directives/index](debugging-directives/index.md)
* [execution-directives/index](execution-directives/index.md)
* [file-and-resource-directives/index](file-and-resource-directives/index.md)
* [file-upload-directives/index](file-upload-directives/index.md)
* [form-field-directives/index](form-field-directives/index.md)
* [future-directives/index](future-directives/index.md)
* [header-directives/index](header-directives/index.md)
* [host-directives/index](host-directives/index.md)
* [marshalling-directives/index](marshalling-directives/index.md)
* [method-directives/index](method-directives/index.md)
* [misc-directives/index](misc-directives/index.md)
* [parameter-directives/index](parameter-directives/index.md)
* [path-directives/index](path-directives/index.md)
* [range-directives/index](range-directives/index.md)
* [respond-with-directives/index](respond-with-directives/index.md)
* [route-directives/index](route-directives/index.md)
* [scheme-directives/index](scheme-directives/index.md)
* [security-directives/index](security-directives/index.md)
* [websocket-directives/index](websocket-directives/index.md)
* [timeout-directives/index](timeout-directives/index.md)

@@@