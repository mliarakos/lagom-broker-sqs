package com.lightbend.lagom.scaladsl.broker.sqs

import com.lightbend.lagom.internal.scaladsl.broker.sqs.ScaladslRegisterTopicProducers
import com.lightbend.lagom.scaladsl.server.LagomServer
import com.lightbend.lagom.spi.persistence.OffsetStore

trait LagomSqsComponents extends LagomSqsClientComponents {
  def lagomServer: LagomServer
  def offsetStore: OffsetStore

  override def topicPublisherName: Option[String] = super.topicPublisherName match {
    case Some(other) =>
      sys.error(
        s"Cannot provide the sqs topic factory as the default topic publisher since a default topic publisher has already been mixed into this cake: $other"
      )
    case None => Some("sqs")
  }

  // Eagerly start topic producers
  new ScaladslRegisterTopicProducers(lagomServer, topicFactory, serviceInfo, actorSystem, offsetStore)(
    sqsClient,
    executionContext,
    materializer
  )
}
