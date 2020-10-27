package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{JsonLdContext, RemoteContextResolution}
import io.circe.{Json, JsonObject}
import monix.bio.IO
import org.apache.jena.rdf.model.Model

/**
  * Json-LD high level API as defined in the ''JsonLdProcessor'' interface of the Json-LD spec.
  * Interface definition for compact, expand, frame, toRdf, fromRdf: https://www.w3.org/TR/json-ld11-api/#the-jsonldprocessor-interface
  * Interface definition for frame: https://www.w3.org/TR/json-ld11-framing/#jsonldprocessor
  */
trait JsonLdApi {
  private[rdf] def compact(input: Json, ctx: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, JsonObject]

  private[rdf] def expand(input: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, Seq[JsonObject]]

  private[rdf] def frame(input: Json, frame: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, JsonObject]

  private[rdf] def toRdf(input: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, Model]

  private[rdf] def fromRdf(input: Model)(implicit
      opts: JsonLdOptions
  ): IO[RdfError, Seq[JsonObject]]

  private[rdf] def context(value: Json)(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, JsonLdContext]

}

object JsonLdApi {
  implicit val jsonLdJavaAPI: JsonLdApi = JsonLdJavaApi
}