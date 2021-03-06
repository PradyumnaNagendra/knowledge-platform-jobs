package org.sunbird.job.task

import java.io.File
import java.util

import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.functions.ActivityAggregatesFunction
import org.sunbird.job.trigger.CountTriggerWithTimeout
import org.sunbird.job.util.FlinkUtil


class ActivityAggregateUpdaterStreamTask(config: ActivityAggregateUpdaterConfig, kafkaConnector: FlinkKafkaConnector) {
  def process(): Unit = {
    implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
    implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
    val progressStream =
      env.addSource(kafkaConnector.kafkaMapSource(config.kafkaInputTopic), config.activityAggregateUpdaterConsumer)
        .uid(config.activityAggregateUpdaterConsumer).setParallelism(config.kafkaConsumerParallelism)
        .rebalance()
        .keyBy(x => x.get("partition").toString)
        .timeWindow(Time.seconds(config.thresholdBatchReadInterval))
        .trigger(new CountTriggerWithTimeout[TimeWindow](config.thresholdBatchReadSize, env.getStreamTimeCharacteristic))
        .process(new ActivityAggregatesFunction(config))
        .name(config.activityAggregateUpdaterFn)
        .uid(config.activityAggregateUpdaterFn)
        .setParallelism(config.activityAggregateUpdaterParallelism)

    progressStream.getSideOutput(config.auditEventOutputTag).addSink(kafkaConnector.kafkaStringSink(config.kafkaAuditEventTopic)).name(config.activityAggregateUpdaterProducer).uid(config.activityAggregateUpdaterProducer)
    env.execute(config.jobName)
  }


}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster
object ActivityAggregateUpdaterStreamTask {

  def main(args: Array[String]): Unit = {
    val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
    val config = configFilePath.map {
      path => ConfigFactory.parseFile(new File(path)).resolve()
    }.getOrElse(ConfigFactory.load("activity-aggregate-updater.conf").withFallback(ConfigFactory.systemEnvironment()))
    val courseAggregator = new ActivityAggregateUpdaterConfig(config)
    val kafkaUtil = new FlinkKafkaConnector(courseAggregator)
    val task = new ActivityAggregateUpdaterStreamTask(courseAggregator, kafkaUtil)
    task.process()
  }
}

// $COVERAGE-ON$
