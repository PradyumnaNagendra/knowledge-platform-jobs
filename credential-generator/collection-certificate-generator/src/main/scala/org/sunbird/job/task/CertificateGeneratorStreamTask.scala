package org.sunbird.job.task

import java.io.File
import java.util

import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.domain.Event
import org.sunbird.job.functions.CertificateGeneratorFunction
import org.sunbird.job.util.{FlinkUtil, HttpUtil}
import org.sunbird.notifier.{NotificationMetaData, NotifierConfig, NotifierFunction}
import org.sunbird.user.feeds.{CreateUserFeedFunction, UserFeedConfig, UserFeedMetaData}

class CertificateGeneratorStreamTask(config: CertificateGeneratorConfig, notifierConfig: NotifierConfig, userFeedConfig: UserFeedConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil) {

  def process(): Unit = {
    implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
    implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
    implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
    implicit val notificationMetaTypeInfo: TypeInformation[NotificationMetaData] = TypeExtractor.getForClass(classOf[NotificationMetaData])
    implicit val userFeedMetaTypeInfo: TypeInformation[UserFeedMetaData] = TypeExtractor.getForClass(classOf[UserFeedMetaData])

    val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)

    val processStreamTask = env.addSource(source)
      .name(config.certificateGeneratorConsumer)
      .uid(config.certificateGeneratorConsumer).setParallelism(config.kafkaConsumerParallelism)
      .rebalance
      .process(new CertificateGeneratorFunction(config, httpUtil))
      .name("collection-certificate-generator")
      .uid("collection-certificate-generator")
      .setParallelism(config.parallelism)

    processStreamTask.getSideOutput(config.failedEventOutputTag)
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaFailedEventTopic))
      .name(config.certificateGeneratorFailedEventProducer)
      .uid(config.certificateGeneratorFailedEventProducer)

    processStreamTask.getSideOutput(config.notifierOutputTag)
      .process(new NotifierFunction(notifierConfig, httpUtil))
      .name("notifier")
      .uid("notifier")
      .setParallelism(1)

    processStreamTask.getSideOutput(config.userFeedOutputTag)
      .process(new CreateUserFeedFunction(userFeedConfig, httpUtil))
      .name("user-feed")
      .uid("user-feed")
      .setParallelism(1)


    env.execute(config.jobName)
  }

}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster
object CertificateGeneratorStreamTask {

  def main(args: Array[String]): Unit = {
    val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
    val config = configFilePath.map {
      path => ConfigFactory.parseFile(new File(path)).resolve()
    }.getOrElse(ConfigFactory.load("collection-certificate-generator.conf").withFallback(ConfigFactory.systemEnvironment()))
    val ccgConfig = new CertificateGeneratorConfig(config)
    val notifierConfig = new NotifierConfig(config)
    val userFeedConfig = new UserFeedConfig(config)
    val kafkaUtil = new FlinkKafkaConnector(ccgConfig)
    val httpUtil = new HttpUtil
    val task = new CertificateGeneratorStreamTask(ccgConfig, notifierConfig, userFeedConfig, kafkaUtil, httpUtil)
    task.process()
  }
}

// $COVERAGE-ON$