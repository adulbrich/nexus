package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.IndexingActionFailed
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{InvalidJsonLdRejection, UnexpectedId}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.{EvaluationError, EvaluationFailure, EvaluationTimeout}
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import scala.reflect.ClassTag

/**
  * Enumeration of schema rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class SchemaRejection(val reason: String) extends Product with Serializable

object SchemaRejection {

  /**
    * Rejection that may occur when fetching a Schema
    */
  sealed abstract class SchemaFetchRejection(override val reason: String) extends SchemaRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a schema at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends SchemaFetchRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a schema at a specific tag, but the provided tag does not
    * exist.
    *
    * @param tag
    *   the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends SchemaFetchRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to update a schema with an id that doesn't exist.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project it belongs to
    */
  final case class SchemaNotFound(id: Iri, project: ProjectRef)
      extends SchemaFetchRejection(s"Schema '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to interact with a schema providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the schema identifier
    */
  final case class InvalidSchemaId(id: String)
      extends SchemaFetchRejection(s"Schema identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create a schema but the id already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends SchemaRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to create a schema where the passed id does not match the id on the payload.
    *
    * @param id
    *   the schema identifier
    * @param payloadId
    *   the schema identifier on the payload
    */
  final case class UnexpectedSchemaId(id: Iri, payloadId: Iri)
      extends SchemaRejection(s"Schema '$id' does not match schema id on payload '$payloadId'.")

  /**
    * Rejection returned when attempting to create/update a schema with a reserved id.
    */
  final case class ReservedSchemaId(id: Iri)
      extends SchemaRejection(s"Schema identifier '$id' is reserved by the platform.")

  /**
    * Rejection returned when attempting to create/update a schema where the payload does not satisfy the SHACL schema
    * constrains.
    *
    * @param id
    *   the schema identifier
    * @param report
    *   the SHACL validation failure report
    */
  final case class InvalidSchema(id: Iri, report: ValidationReport)
      extends SchemaRejection(s"Schema '$id' failed to validate against the constraints defined in the SHACL schema.")

  /**
    * Rejection returned when failed to resolve some owl imports.
    *
    * @param id
    *   the schema identifier
    * @param schemaImports
    *   the schema imports that weren't successfully resolved
    * @param resourceImports
    *   the resource imports that weren't successfully resolved
    * @param nonOntologyResources
    *   resolved resources which are not ontologies
    */
  final case class InvalidSchemaResolution(
      id: Iri,
      schemaImports: Map[ResourceRef, ResourceResolutionReport],
      resourceImports: Map[ResourceRef, ResourceResolutionReport],
      nonOntologyResources: Set[ResourceRef]
  ) extends SchemaRejection(
        s"Failed to resolve imports ${(schemaImports.keySet ++ resourceImports.keySet ++ nonOntologyResources)
          .mkString("'", "', '", "'")} for schema '$id'."
      )

  /**
    * Rejection returned when attempting to create a SHACL engine.
    *
    * @param id
    *   the schema identifier
    * @param details
    *   the SHACL engine errors
    */
  final case class SchemaShaclEngineRejection(id: Iri, details: String)
      extends SchemaRejection(s"Schema '$id' failed to produce a SHACL engine for the SHACL schema.")

  /**
    * Rejection returned when attempting to update/deprecate a schema that is already deprecated.
    *
    * @param id
    *   the schema identifier
    */
  final case class SchemaIsDeprecated(id: Iri) extends SchemaFetchRejection(s"Schema '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current schema, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends SchemaRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the schema may have been updated since last seen."
      )

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends SchemaFetchRejection(rejection.reason)

  /**
    * Signals a rejection caused when interacting with the organizations API
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends SchemaFetchRejection(rejection.reason)

  /**
    * Signals a rejection caused when interacting with the resolvers resolution API
    */
  final case class WrappedResolverResolutionRejection(rejection: ResolverResolutionRejection)
      extends SchemaRejection(rejection.reason)

  /**
    * Signals a rejection caused by a failure to perform indexing.
    */
  final case class WrappedIndexingActionRejection(rejection: IndexingActionFailed)
      extends SchemaRejection(rejection.reason)

  /**
    * Signals an error converting the source Json to JsonLD
    */
  final case class InvalidJsonLdFormat(idOpt: Option[Iri], rdfError: RdfError)
      extends SchemaRejection(s"Schema${idOpt.fold("")(id => s" '$id'")} has invalid JSON-LD payload.")

  /**
    * Rejection returned when the returned state is the initial state after a Schemas.evaluation plus a Schemas.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a
    * current
    */
  final case class UnexpectedInitialState(id: Iri)
      extends SchemaRejection(s"Unexpected initial state for schema '$id'.")

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation failed
    */
  final case class SchemaEvaluationError(err: EvaluationError) extends SchemaRejection("Unexpected evaluation error")

  implicit def schemasRejectionEncoder(implicit C: ClassTag[SchemaCommand]): Encoder.AsObject[SchemaRejection] = {
    def importsAsJson(imports: Map[ResourceRef, ResourceResolutionReport]) =
      Json.fromValues(
        imports.map { case (ref, report) =>
          Json.obj("resourceRef" -> ref.asJson, "report" -> report.asJson)
        }
      )

    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case SchemaEvaluationError(EvaluationFailure(C(cmd), _))                              =>
          val reason = s"Unexpected failure while evaluating the command '${simpleName(cmd)}' for schema '${cmd.id}'"
          JsonObject(keywords.tpe -> "SchemaEvaluationFailure".asJson, "reason" -> reason.asJson)
        case SchemaEvaluationError(EvaluationTimeout(C(cmd), t))                              =>
          val reason = s"Timeout while evaluating the command '${simpleName(cmd)}' for schema '${cmd.id}' after '$t'"
          JsonObject(keywords.tpe -> "SchemaEvaluationTimeout".asJson, "reason" -> reason.asJson)
        case WrappedOrganizationRejection(rejection)                                          => rejection.asJsonObject
        case WrappedProjectRejection(rejection)                                               => rejection.asJsonObject
        case SchemaShaclEngineRejection(_, details)                                           => obj.add("details", details.asJson)
        case InvalidJsonLdFormat(_, rdf)                                                      => obj.add("rdf", rdf.asJson)
        case InvalidSchema(_, report)                                                         => obj.addContext(contexts.shacl).add("details", report.json)
        case InvalidSchemaResolution(_, schemaImports, resourceImports, nonOntologyResources) =>
          obj
            .add("schemaImports", importsAsJson(schemaImports))
            .add("resourceImports", importsAsJson(resourceImports))
            .add("nonOntologyResources", nonOntologyResources.asJson)
        case _: SchemaNotFound                                                                => obj.add(keywords.tpe, "ResourceNotFound".asJson)
        case _                                                                                => obj
      }
    }
  }

  implicit final val schemasRejectionJsonLdEncoder: JsonLdEncoder[SchemaRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val schemaJsonLdRejectionMapper: Mapper[InvalidJsonLdRejection, SchemaRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedSchemaId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
  }

  implicit val schemaProjectRejectionMapper: Mapper[ProjectRejection, SchemaFetchRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val schemaOrgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    WrappedOrganizationRejection.apply

  implicit val schemaIndexingActionRejectionMapper: Mapper[IndexingActionFailed, WrappedIndexingActionRejection] =
    (value: IndexingActionFailed) => WrappedIndexingActionRejection(value)

  implicit final val evaluationErrorMapper: Mapper[EvaluationError, SchemaRejection] = SchemaEvaluationError.apply

}
