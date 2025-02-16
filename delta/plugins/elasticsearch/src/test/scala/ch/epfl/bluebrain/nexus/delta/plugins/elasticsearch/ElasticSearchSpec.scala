package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClientSpec
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingSpec
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metric.ProjectEventMetricsStreamSpec
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class ElasticSearchSpec
    extends Suites(
      new ElasticSearchClientSpec,
      new ElasticSearchIndexingSpec,
      new ElasticSearchViewsQuerySpec,
      new ProjectEventMetricsStreamSpec
    )
    with ElasticSearchDocker
    with DockerTestKit
