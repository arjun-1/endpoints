# scalaj-http

Client interpreter backed by [scalaj-http](https://github.com/scalaj/scalaj-http).

@@@vars
~~~ scala
"org.julienrf" %% "endpoints-scalaj-client" % "$version$"
~~~
@@@

@scaladoc[API documentation](endpoints.scalaj.client.index)

The `Endpoints` interpreter fixes the `Endpoint[A, B]` type
to a type that provide methods to invoke the endpoint synchronously
or asynchronously.

Given the following endpoint definition:

@@snip [EndpointsDocs.scala](/algebras/algebra/src/test/scala/endpoints/algebra/EndpointsDocs.scala) { #endpoint-definition }

It can be asynchronously invoked as follows:

@@snip [EndpointsDocs.scala](/scalaj/client/src/test/scala/endpoints/scalaj/client/EndpointsDocs.scala) { #invocation }
