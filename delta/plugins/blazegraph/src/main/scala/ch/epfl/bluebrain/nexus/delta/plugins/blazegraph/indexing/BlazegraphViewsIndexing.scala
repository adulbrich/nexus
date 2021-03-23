package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.BlazegraphViewsCache
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.BlazegraphIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import monix.execution.Scheduler

object BlazegraphViewsIndexing {
  private val logger: Logger = Logger[BlazegraphViewsIndexing.type]

  /**
    * Populate the blazegraph views cache from the event log
    */
  def populateCache(config: ExternalIndexingConfig, views: BlazegraphViews, cache: BlazegraphViewsCache)(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[Unit] = {
    def onEvent = (event: BlazegraphViewEvent) =>
      views
        .fetch(event.id, event.project)
        .redeemCauseWith(_ => IO.unit, res => cache.put(ViewRef(res.value.project, res.value.id), res))

    apply("BlazegraphViewsIndex", config, views, onEvent)
  }

  /**
    * Starts indexing streams from the event log
    */
  def startIndexingStreams(
      config: ExternalIndexingConfig,
      views: BlazegraphViews,
      coordinator: BlazegraphIndexingCoordinator
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] = {
    def onEvent(event: BlazegraphViewEvent) = coordinator.run(event.id, event.project, event.rev)
    apply("BlazegraphIndexingCoordinatorScan", config, views, onEvent)
  }

  private def apply(
      name: String,
      config: ExternalIndexingConfig,
      views: BlazegraphViews,
      onEvent: BlazegraphViewEvent => Task[Unit]
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] =
    DaemonStreamCoordinator.run(
      name,
      streamTask = Task.delay(
        views.events(Offset.noOffset).evalMap { e => onEvent(e.event) }
      ),
      retryStrategy = RetryStrategy.retryOnNonFatal(config.retry, logger, name)
    )

}
