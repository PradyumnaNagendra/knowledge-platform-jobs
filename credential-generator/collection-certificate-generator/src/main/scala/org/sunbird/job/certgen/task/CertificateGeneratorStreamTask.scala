package org.sunbird.job.certgen.task

import java.io.File
import java.util

import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.scala.{OutputTag, StreamExecutionEnvironment}
import org.sunbird.incredible.StorageParams
import org.sunbird.incredible.processor.store.StorageService
import org.sunbird.job.certgen.domain.Event
import org.sunbird.job.certgen.functions.{CertificateGeneratorFunction, CreateUserFeedFunction, NotificationMetaData, NotifierFunction, UserFeedMetaData}
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

class CertificateGeneratorStreamTask(config: CertificateGeneratorConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil, storageService: StorageService) {

  def process(): Unit = {
    implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
    implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
    implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
    implicit val notificationMetaTypeInfo: TypeInformation[NotificationMetaData] = TypeExtractor.getForClass(classOf[NotificationMetaData])
    implicit val userFeedMetaTypeInfo: TypeInformation[UserFeedMetaData] = TypeExtractor.getForClass(classOf[UserFeedMetaData])

    val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)

    env.getCheckpointConfig.setCheckpointTimeout(900000L)
    env.getCheckpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE)

    val processStreamTask = env.addSource(source)
      .name(config.certificateGeneratorConsumer)
      .uid(config.certificateGeneratorConsumer).setParallelism(config.kafkaConsumerParallelism)
      .rebalance
      .process(new CertificateGeneratorFunction(config, httpUtil, storageService))
      .name("collection-certificate-generator")
      .uid("collection-certificate-generator")
      .setParallelism(config.parallelism)

    processStreamTask.getSideOutput(config.failedtag)
      .addSink(kafkaConnector.kafkaStringSink("cert.failed.topic"))
      .name("collection-certificate-generator-failed-events-sink")
      .uid("collection-certificate-generator-failed-events-sink")

    processStreamTask.getSideOutput(config.auditEventOutputTag)
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaAuditEventTopic))
      .name(config.certificateGeneratorAuditProducer)
      .uid(config.certificateGeneratorAuditProducer)

    processStreamTask.getSideOutput(config.notifierOutputTag)
      .process(new NotifierFunction(config, httpUtil))
      .name("notifier")
      .uid("notifier")
      .setParallelism(8)

    processStreamTask.getSideOutput(config.userFeedOutputTag)
      .process(new CreateUserFeedFunction(config, httpUtil))
      .name("user-feed")
      .uid("user-feed")
      .setParallelism(8)


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
    val kafkaUtil = new FlinkKafkaConnector(ccgConfig)
    val httpUtil = new HttpUtil
    val storageParams: StorageParams = StorageParams(ccgConfig.storageType, ccgConfig.azureStorageKey, ccgConfig.azureStorageSecret, ccgConfig.containerName)
    val storageService: StorageService = new StorageService(storageParams)
    val task = new CertificateGeneratorStreamTask(ccgConfig, kafkaUtil, httpUtil, storageService)
    task.process()
  }
}

// $COVERAGE-ON$
