package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

/**
  * A wrapper for a collection of permissions
  */
final case class PermissionSet(permissions: Set[Permission]) extends AnyVal

object PermissionSet {

  implicit final val permissionSetEncoder: Encoder.AsObject[PermissionSet] = deriveEncoder
  implicit final val permissionSetDecoder: Decoder[PermissionSet]          = deriveDecoder

  implicit final val permissionSetJsonLdEncoder: JsonLdEncoder[PermissionSet] =
    JsonLdEncoder.fromCirce(id = BNode.random, iriContext = contexts.permissions)

}