package ch.epfl.bluebrain.nexus.delta.service.acls

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.Acls.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.service.acls.AclsImpl.{AclsAggregate, AclsCache}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor.persistenceId
import ch.epfl.bluebrain.nexus.delta.sourcing.processor._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

final class AclsImpl private (
    agg: AclsAggregate,
    permissions: Permissions,
    eventLog: EventLog[Envelope[AclEvent]],
    index: AclsCache
) extends Acls {

  private val minimum: Set[Permission] = permissions.minimum

  override def fetch(address: AclAddress): IO[AclNotFound, AclResource] =
    agg
      .state(address.string)
      .map(_.toResource(address, minimum))
      .flatMap(IO.fromOption(_, AclNotFound(address)))
      .named("fetchAcl", moduleType)

  override def fetchWithAncestors(address: AclAddress): UIO[AclCollection] =
    super.fetchWithAncestors(address).named("fetchWithAncestors", moduleType)

  override def fetchAt(address: AclAddress, rev: Long): IO[AclRejection.NotFound, AclResource] =
    eventLog
      .fetchStateAt(persistenceId(moduleType, address.string), rev, Initial, Acls.next)
      .bimap(RevisionNotFound(rev, _), _.toResource(address, minimum))
      .flatMap(IO.fromOption(_, AclNotFound(address)))
      .named("fetchAclAt", moduleType)

  override def list(filter: AclAddressFilter): UIO[AclCollection]                              =
    index.values
      .map { as =>
        val col             = AclCollection(as: _*)
        val rootResourceOpt = col.value.get(AclAddress.Root) match {
          case None if filter.withAncestors => Initial.toResource(AclAddress.Root, minimum)
          case resourceOpt                  => resourceOpt
        }
        rootResourceOpt.fold(col)(rootResource => col + rootResource).fetch(filter)
      }
      .named("listAcls", moduleType, Map("withAncestors" -> filter.withAncestors))

  override def listSelf(filter: AclAddressFilter)(implicit caller: Caller): UIO[AclCollection] =
    list(filter)
      .map(_.filter(caller.identities))
      .named("listSelfAcls", moduleType, Map("withAncestors" -> filter.withAncestors))

  override def events(offset: Offset): Stream[Task, Envelope[AclEvent]]                        =
    eventLog.eventsByTag(moduleType, offset)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[AclEvent]] =
    eventLog.currentEventsByTag(moduleType, offset)

  override def replace(acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(ReplaceAcl(acl, rev, caller)).named("replaceAcls", moduleType)

  override def append(acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(AppendAcl(acl, rev, caller)).named("appendAcls", moduleType)

  override def subtract(acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(SubtractAcl(acl, rev, caller)).named("subtractAcls", moduleType)

  override def delete(address: AclAddress, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(DeleteAcl(address, rev, caller)).named("deleteAcls", moduleType)

  private def eval(cmd: AclCommand): IO[AclRejection, AclResource] =
    for {
      evaluationResult <- agg.evaluate(cmd.address.string, cmd).mapError(_.value)
      resourceOpt       = evaluationResult.state.toResource(cmd.address, minimum)
      resource         <- IO.fromOption(resourceOpt, UnexpectedInitialState(cmd.address))
      _                <- index.put(cmd.address, resource)
      _                <- index.flushChanges
    } yield resource

}

object AclsImpl {

  type AclsAggregate = Aggregate[String, AclState, AclCommand, AclEvent, AclRejection]

  type AclsCache = KeyValueStore[AclAddress, AclResource]

  private val logger: Logger = Logger[AclsImpl]

  def aggregate(
      permissions: Permissions,
      realms: Realms,
      aggregateConfig: AggregateConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[AclsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = AclState.Initial,
      next = Acls.next,
      evaluate = Acls.evaluate(permissions, realms),
      tagger = EventTags.forUnScopedEvent(moduleType),
      snapshotStrategy = aggregateConfig.snapshotStrategy.strategy,
      stopStrategy = aggregateConfig.stopStrategy.persistentStrategy
    )
    ShardedAggregate
      .persistentSharded(
        definition = definition,
        config = aggregateConfig.processor
      )
  }

  def cache(aclsConfig: AclsConfig)(implicit as: ActorSystem[Nothing]): AclsCache = {
    implicit val cfg: KeyValueStoreConfig  = aclsConfig.keyValueStore
    val clock: (Long, AclResource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed(moduleType, clock)
  }

  private def startIndexing(
      config: AclsConfig,
      eventLog: EventLog[Envelope[AclEvent]],
      index: AclsCache,
      acls: Acls
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler) =
    DaemonStreamCoordinator.run(
      "AclsIndex",
      stream = eventLog
        .eventsByTag(moduleType, Offset.noOffset)
        .mapAsync(config.cacheIndexing.concurrency)(envelope =>
          acls.fetch(envelope.event.address).redeemCauseWith(_ => IO.unit, res => index.put(res.value.address, res))
        ),
      retryStrategy = RetryStrategy.retryOnNonFatal(config.cacheIndexing.retry, logger, "acls indexing")
    )

  private def apply(
      agg: AclsAggregate,
      permissions: Permissions,
      eventLog: EventLog[Envelope[AclEvent]],
      cache: AclsCache
  ): AclsImpl =
    new AclsImpl(agg, permissions, eventLog, cache)

  /**
    * Constructs an [[AclsImpl]] instance.
    */
  final def apply(
      agg: AclsAggregate,
      cache: AclsCache,
      config: AclsConfig,
      permissions: Permissions,
      eventLog: EventLog[Envelope[AclEvent]]
  )(implicit
      as: ActorSystem[Nothing],
      sc: Scheduler,
      uuidF: UUIDF
  ): Task[AclsImpl] = {
    val acls = AclsImpl(agg, permissions, eventLog, cache)
    startIndexing(config, eventLog, cache, acls).as(acls)
  }
}
