package org.sunbird.collectioncomplete.domain

import org.sunbird.job.domain.reader.JobRequest

import scala.collection.JavaConverters
import scala.collection.JavaConverters._

class Event(eventMap: java.util.Map[String, Any])  extends JobRequest(eventMap) {

  def action: String = readOrDefault[String]("edata.action", "")

  def batchId: String = readOrDefault[String]("edata.batchId", "")

  def courseId: String = readOrDefault[String]("edata.courseId", "")

  def userIds: java.util.List[String] = readOrDefault[List[String]]("edata.userIds", List[String]()).asJava

  def eData: java.util.Map[String, AnyRef] = JavaConverters.mapAsJavaMap(readOrDefault[Map[String, AnyRef]]("edata", Map[String, AnyRef]()))

}
