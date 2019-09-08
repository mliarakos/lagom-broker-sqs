package com.lightbend.lagom.scaladsl.broker.sqs

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactory
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactoryProvider
import com.lightbend.lagom.internal.scaladsl.broker.sqs.SqsTopicFactory
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import scala.concurrent.ExecutionContext

trait LagomSqsClientComponents extends TopicFactoryProvider {
  def serviceInfo: ServiceInfo
  def actorSystem: ActorSystem
  def materializer: Materializer
  def executionContext: ExecutionContext

  // TODO: SqsAsyncClient config
  lazy val sqsClient: SqsAsyncClient = {
    val client = SqsAsyncClient
      .builder()
      .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
      .build()
    actorSystem.registerOnTermination(client.close())

    client
  }

  lazy val topicFactory: TopicFactory =
    new SqsTopicFactory(serviceInfo, actorSystem)(sqsClient, materializer, executionContext)

  override def optionalTopicFactory: Option[TopicFactory] = Some(topicFactory)
}
