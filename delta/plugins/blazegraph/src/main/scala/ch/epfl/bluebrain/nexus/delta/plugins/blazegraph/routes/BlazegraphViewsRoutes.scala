package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}
import akka.persistence.query.NoOffset
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{permissions, BlazegraphViewRejection, SparqlLink, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes.RestartView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.instances.OffsetInstances._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, ProgressStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProgressesStatistics, Projects}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * The Blazegraph views routes
  *
  * @param views      the blazegraph views operations bundle
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  * @param progresses the statistics of the progresses for the blazegraph views
  * @param restartView  the action to restart a view indexing process triggered by a client
  */
class BlazegraphViewsRoutes(
    views: BlazegraphViews,
    viewsQuery: BlazegraphViewsQuery,
    identities: Identities,
    acls: Acls,
    projects: Projects,
    progresses: ProgressesStatistics,
    restartView: RestartView
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    pc: PaginationConfig
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with DeltaDirectives
    with BlazegraphViewsDirectives {

  import baseUri.prefixSegment
  implicit private val viewStatisticEncoder: Encoder.AsObject[ProgressStatistics]    =
    deriveEncoder[ProgressStatistics].mapJsonObject(_.add(keywords.tpe, "ViewStatistics".asJson))
  implicit private val viewStatisticJsonLdEncoder: JsonLdEncoder[ProgressStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.statistics))

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUriOnUnderscore("views")) {
      extractCaller { implicit caller =>
        concat(
          pathPrefix("views") {
            projectRef(projects).apply { implicit ref =>
              // Create a view without id segment
              concat(
                (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash) { source =>
                  operationName(s"$prefixSegment/views/{org}/{project}") {
                    authorizeFor(ref, permissions.write).apply {
                      emit(
                        Created,
                        views.create(ref, source).mapValue(_.metadata).rejectWhen(decodingFailedOrViewNotFound)
                      )
                    }
                  }
                },
                idSegment { id =>
                  concat(
                    (pathEndOrSingleSlash & operationName(s"$prefixSegment/views/{org}/{project}/{id}")) {
                      concat(
                        put {
                          authorizeFor(ref, permissions.write).apply {
                            (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                              case (None, source)      =>
                                // Create a view with id segment
                                emit(
                                  Created,
                                  views
                                    .create(id, ref, source)
                                    .mapValue(_.metadata)
                                    .rejectWhen(decodingFailedOrViewNotFound)
                                )
                              case (Some(rev), source) =>
                                // Update a view
                                emit(
                                  views
                                    .update(id, ref, rev, source)
                                    .mapValue(_.metadata)
                                    .rejectWhen(decodingFailedOrViewNotFound)
                                )
                            }
                          }
                        },
                        (delete & parameter("rev".as[Long])) { rev =>
                          // Deprecate a view
                          authorizeFor(ref, permissions.write).apply {
                            emit(views.deprecate(id, ref, rev).mapValue(_.metadata).rejectOn[ViewNotFound])
                          }
                        },
                        // Fetch a view
                        get {
                          fetch(id, ref)
                        }
                      )
                    },
                    // Query a blazegraph view
                    (pathPrefix("sparql") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/sparql") {
                        concat(
                          //Query using GET and `query` parameter
                          (get & parameter("query".as[SparqlQuery])) { query =>
                            emit(viewsQuery.query(id, ref, query))
                          },
                          //Query using POST and request body
                          (post & entity(as[SparqlQuery])) { query =>
                            emit(viewsQuery.query(id, ref, query))
                          }
                        )
                      }
                    },
                    // Fetch a blazegraph view statistics
                    (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                        authorizeFor(ref, permissions.read).apply {
                          emit(
                            views
                              .fetchIndexingView(id, ref)
                              .flatMap(v => progresses.statistics(ref, BlazegraphViews.projectionId(v)))
                              .rejectOn[ViewNotFound]
                          )
                        }
                      }
                    },
                    // Manage an blazegraph view offset
                    (pathPrefix("offset") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                        concat(
                          // Fetch a blazegraph view offset
                          (get & authorizeFor(ref, permissions.read)) {
                            emit(
                              views
                                .fetchIndexingView(id, ref)
                                .flatMap(v => progresses.offset(BlazegraphViews.projectionId(v)))
                                .rejectOn[ViewNotFound]
                            )
                          },
                          // Remove an blazegraph view offset (restart the view)
                          (delete & authorizeFor(ref, permissions.write)) {
                            emit(
                              views
                                .fetchIndexingView(id, ref)
                                .flatMap { r => restartView(r.value.id, r.value.project) }
                                .as(NoOffset)
                                .rejectOn[ViewNotFound]
                            )
                          }
                        )
                      }
                    },
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch tags for a view
                          get {
                            fetchMap(id, ref, resource => Tags(resource.value.tags))
                          },
                          // Tag a view
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeFor(ref, permissions.write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(
                                  Created,
                                  views.tag(id, ref, tag, tagRev, rev).mapValue(_.metadata).rejectOn[ViewNotFound]
                                )
                              }
                            }
                          }
                        )
                      }
                    },
                    // Fetch a view original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/source") {
                        fetchSource(id, ref, _.value.source)
                      }
                    },
                    //Incoming/outgoing links for views
                    incomingOutgoing(id, ref)
                  )
                }
              )
            }
          },
          //Handle all other incoming and outgoing links
          pathPrefix(Segment) { segment =>
            projectRef(projects).apply { ref =>
              // if we are on the path /resources/{org}/{proj}/ we need to consume the {schema} segment before consuming the {id}
              consumeIdSegmentIf(segment == "resources") {
                idSegment { id =>
                  incomingOutgoing(id, ref)
                }
              }
            }
          }
        )
      }
    }

  private def consumeIdSegmentIf(condition: Boolean): Directive0 =
    if (condition) idSegment.flatMap(_ => pass)
    else pass

  private def incomingOutgoing(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    concat(
      (pathPrefix("incoming") & fromPaginated & pathEndOrSingleSlash & extractUri) { (pagination, uri) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(ContextValue(Vocabulary.contexts.metadata), pagination, uri)

        authorizeFor(ref, resources.read).apply {
          emit(viewsQuery.incoming(id, ref, pagination))
        }
      },
      (pathPrefix("outgoing") & fromPaginated & pathEndOrSingleSlash & extractUri & parameter(
        "includeExternalLinks".as[Boolean] ? true
      )) { (pagination, uri, includeExternal) =>
        implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[SparqlLink]] =
          searchResultsJsonLdEncoder(ContextValue(Vocabulary.contexts.metadata), pagination, uri)

        authorizeFor(ref, resources.read).apply {
          emit(viewsQuery.outgoing(id, ref, pagination, includeExternal))
        }
      }
    )

  private def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    fetchMap(id, ref, identity)

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      f: ViewResource => A
  )(implicit caller: Caller) =
    authorizeFor(ref, permissions.read).apply {
      fetchResource(
        rev => emit(views.fetchAt(id, ref, rev).map(f).rejectOn[ViewNotFound]),
        tag => emit(views.fetchBy(id, ref, tag).map(f).rejectOn[ViewNotFound]),
        emit(views.fetch(id, ref).map(f).rejectOn[ViewNotFound])
      )
    }

  private def fetchSource(id: IdSegment, ref: ProjectRef, f: ViewResource => Json)(implicit caller: Caller) =
    authorizeFor(ref, permissions.read).apply {
      fetchResource(
        rev => emit(views.fetchAt(id, ref, rev).map(f).rejectOn[ViewNotFound]),
        tag => emit(views.fetchBy(id, ref, tag).map(f).rejectOn[ViewNotFound]),
        emit(views.fetch(id, ref).map(f).rejectOn[ViewNotFound])
      )
    }

  private val decodingFailedOrViewNotFound: PartialFunction[BlazegraphViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }
}

object BlazegraphViewsRoutes {

  type RestartView = (Iri, ProjectRef) => UIO[Unit]

  /**
    * @return the [[Route]] for BlazegraphViews
    */
  def apply(
      views: BlazegraphViews,
      viewsQuery: BlazegraphViewsQuery,
      identities: Identities,
      acls: Acls,
      projects: Projects,
      progresses: ProgressesStatistics,
      restartView: RestartView
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      pc: PaginationConfig
  ): Route = {
    new BlazegraphViewsRoutes(views, viewsQuery, identities, acls, projects, progresses, restartView).routes
  }
}
