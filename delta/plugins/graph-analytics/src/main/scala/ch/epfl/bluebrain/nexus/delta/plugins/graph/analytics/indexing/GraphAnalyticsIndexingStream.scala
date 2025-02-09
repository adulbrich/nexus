package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.RelationshipResolution
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingStream.GraphDocument
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdPathValue.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdPathValueCollection.{JsonLdProperties, JsonLdRelationships}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.{JsonLdPathValue, JsonLdPathValueCollection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.{EventExchangeValue, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import fs2.Stream
import io.circe.literal._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for graph analytics
  */
final class GraphAnalyticsIndexingStream(
    client: ElasticSearchClient,
    indexingSource: IndexingSource,
    cache: ProgressesCache,
    config: ExternalIndexingConfig,
    projection: Projection[Unit],
    relationshipResolution: RelationshipResolution
)(implicit api: JsonLdApi, cr: RemoteContextResolution, sc: Scheduler)
    extends IndexingStream[GraphAnalyticsView] {

  @SuppressWarnings(Array("OptionGet"))
  private def relationshipsQuery(resources: Map[Iri, Set[Iri]]): JsonObject = {
    val terms = resources.map { case (id, _) => id.asJson }.asJson
    json"""
    {
      "query": {
        "bool": {
          "filter": {
            "terms": {
              "relationshipCandidates.@id": $terms
            }
          }
        }
      },
      "script": {
        "id": "updateRelationships",
        "params": {
          "resources": $resources
        }
      }
    }          
    """.asObject.get
  }

  override def apply(
      view: ViewIndex[GraphAnalyticsView],
      strategy: IndexingStream.ProgressStrategy
  ): Task[Stream[Task, Unit]] = Task.delay {
    implicit val metricsConfig: KamonMetricsConfig = ViewIndex.metricsConfig(view, nxv + "GraphAnalytics")
    val index                                      = idx(view)
    Stream
      .eval {
        // Evaluates strategy and set/get the appropriate progress
        client.createIndex(index, Some(view.value.mapping), None) >> handleProgress(strategy, view.projectionId)
      }
      .flatMap { progress =>
        indexingSource(view.projectRef, progress.offset, view.resourceTag)
          .evalMapFilterValue {
            case TagNotFound(_)                               => Task.none
            case eventExchangeValue: EventExchangeValue[_, _] =>
              // Creates a JsonLdPathValueCollection from the event exchange response
              fromEventExchange(view.projectRef, eventExchangeValue).map(Some(_))
          }
          .runAsyncUnit { list =>
            IO.when(list.nonEmpty) {
              val (idTypesMap, bulkOps) = list.foldLeft((Map.empty[Iri, Set[Iri]], List.empty[ElasticSearchBulk])) {
                case ((typesMap, bulkList), doc) =>
                  (
                    typesMap + (doc.id -> doc.types),
                    ElasticSearchBulk.Index(index, doc.id.toString, doc.value) :: bulkList
                  )
              }
              // Pushes INDEX/DELETE Elasticsearch bulk operations & performs an update by query
              client.bulk(bulkOps, Refresh.WaitFor) >>
                client.updateByQuery(relationshipsQuery(idTypesMap), Set(index.value))
            }
          }
          .flatMap(Stream.chunk)
          .map(_.void)
          // Persist progress in cache and in primary store
          .persistProgressWithCache(
            progress,
            view.projectionId,
            projection,
            cache.put(view.projectionId, _),
            config.projection,
            config.cache
          )
          .enableMetrics
          .map(_.value)
      }
  }

  private def handleProgress(
      strategy: ProgressStrategy,
      projectionId: ViewProjectionId
  ): Task[ProjectionProgress[Unit]] =
    strategy match {
      case ProgressStrategy.Continue    =>
        for {
          progress <- projection.progress(projectionId)
          _        <- cache.put(projectionId, progress)
        } yield progress
      case ProgressStrategy.FullRestart =>
        cache.remove(projectionId) >>
          cache.put(projectionId, NoProgress) >>
          projection.recordProgress(projectionId, NoProgress).as(NoProgress)
    }

  private def idx(view: ViewIndex[_]): IndexLabel =
    IndexLabel.unsafe(view.index)

  private def fromEventExchange[A, M](
      project: ProjectRef,
      exchangedValue: EventExchangeValue[A, M]
  )(implicit cr: RemoteContextResolution): IO[RdfError, GraphDocument] = {
    val res     = exchangedValue.value.resource
    val encoder = exchangedValue.value.encoder
    for {
      expanded          <- encoder.expand(res.value)
      pathProperties     = JsonLdProperties.fromExpanded(expanded)
      pathRelationships <- relationships(pathProperties.relationshipCandidates, project)
      paths              = JsonLdPathValueCollection(pathProperties, pathRelationships)
      types              = Json.obj(keywords.id -> res.id.asJson).addIfNonEmpty(keywords.tpe, res.types)
    } yield GraphDocument(res.id, res.types, paths.asJson deepMerge types)
  }

  private def relationships(candidates: Map[Iri, JsonLdPathValue], projectRef: ProjectRef): UIO[JsonLdRelationships] = {
    UIO
      .parTraverseN(parallelism = 10)(candidates.toSeq) { case (id, pathValue) =>
        relationshipResolution(projectRef, id).map { relationshipsOpt =>
          relationshipsOpt.map(relationship => pathValue.withMeta(Metadata(Some(relationship.id), relationship.types)))
        }
      }
      .map(list => JsonLdRelationships(list.flatten))

  }

}

object GraphAnalyticsIndexingStream {

  final case class GraphDocument(id: Iri, types: Set[Iri], value: Json)

}
