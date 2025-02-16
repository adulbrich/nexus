package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{ExpandedJsonLd, ExpandedJsonLdCursor}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

import scala.annotation.nowarn

/**
  * Definition of a pipe to include in a view
  * @param name
  *   the identifier of the pipe to apply
  * @param description
  *   a description of what is expected from the pipe
  * @param config
  *   the config to provide to the pipe
  */
final case class PipeDef(name: String, description: Option[String], config: Option[ExpandedJsonLd]) {

  def description(value: String): PipeDef = copy(description = Some(value))

}
@nowarn("cat=unused")
object PipeDef {

  /**
    * Create a pipe def without config
    * @param name
    *   the identifier of the pipe
    */
  def noConfig(name: String): PipeDef = PipeDef(name, None, None)

  /**
    * Create a pipe with the provided config
    * @param name
    *   the identifier of the pipe
    * @param config
    *   the config to apply
    */
  def withConfig(name: String, config: ExpandedJsonLd): PipeDef = PipeDef(name, None, Some(config))

  implicit val pipeDefEncoder: Encoder.AsObject[PipeDef] = {
    implicit val expandedEncoder: Encoder[ExpandedJsonLd] = Encoder.instance(_.json)
    deriveEncoder[PipeDef]
  }

  implicit val pipeDefDecoder: Decoder[PipeDef] = {
    implicit val expandedDecoder: Decoder[ExpandedJsonLd] =
      Decoder.decodeJson.emap(ExpandedJsonLd.expanded(_).leftMap(_.getMessage))
    deriveDecoder[PipeDef].map {
      case p if p.config.isDefined => p.copy(config = p.config.map(_.copy(rootId = nxv + p.name)))
      case p                       => p
    }
  }

  implicit val pipeDefJsonLdEncoder: JsonLdEncoder[PipeDef] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.pipeline))

  implicit val pipeDefJsonLdDecoder: JsonLdDecoder[PipeDef] = {
    implicit val expandedJsonLdDecoder: JsonLdDecoder[ExpandedJsonLd] = (cursor: ExpandedJsonLdCursor) => cursor.focus
    deriveJsonLdDecoder[PipeDef].map {
      case p if p.config.isDefined => p.copy(config = p.config.map(_.copy(rootId = nxv + p.name)))
      case p                       => p
    }
  }
}
