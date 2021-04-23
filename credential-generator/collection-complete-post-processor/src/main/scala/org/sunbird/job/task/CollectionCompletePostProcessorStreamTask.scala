package org.sunbird.job.task

import java.io.File
import java.util

import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.sunbird.collectioncomplete.domain.Event
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.functions.CollectionCompletePostProcessor
import org.sunbird.job.util.FlinkUtil

class CollectionCompletePostProcessorStreamTask(config: CollectionCompletePostProcessorConfig, kafkaConnector: FlinkKafkaConnector) {
  def process(): Unit = {
    implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
    implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
    implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
    val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)

    val progressStream =
      env.addSource(source).name(config.certificatePreProcessorConsumer)
        .uid(config.certificatePreProcessorConsumer).setParallelism(config.kafkaConsumerParallelism)
        .rebalance
        .process(new CollectionCompletePostProcessor(config))
        .name("collection-complete-post-processor").uid("collection-complete-post-processor")
        .setParallelism(config.parallelism)

    progressStream.getSideOutput(config.generateCertificateOutputTag).addSink(kafkaConnector.kafkaStringSink(config.kafkaOutputTopic))
      .name(config.generateCertificateProducer).uid(config.generateCertificateProducer).setParallelism(config.generateCertificateParallelism)
    progressStream.getSideOutput(config.failedEventOutputTag).addSink(kafkaConnector.kafkaStringSink(config.kafkaFailedEventTopic)).name(config.generateCertificateFailedEventProducer).uid(config.generateCertificateFailedEventProducer)
    env.execute(config.jobName)
  }
}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster
object CollectionCompletePostProcessorStreamTask {
  def main(args: Array[String]): Unit = {
    val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
    val config = configFilePath.map {
      path => ConfigFactory.parseFile(new File(path)).resolve()
    }.getOrElse(ConfigFactory.load("collection-complete-post-processor.conf").withFallback(ConfigFactory.systemEnvironment()))
    val certificatePreProcessorConfig = new CollectionCompletePostProcessorConfig(config)
    val kafkaUtil = new FlinkKafkaConnector(certificatePreProcessorConfig)
    val task = new CollectionCompletePostProcessorStreamTask(certificatePreProcessorConfig, kafkaUtil)
    task.process()
  }
}

// $COVERAGE-ON$
